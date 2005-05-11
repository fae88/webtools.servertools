/**********************************************************************
 * Copyright (c) 2003, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - Initial API and implementation
 **********************************************************************/
package org.eclipse.wst.server.core.internal;

import java.util.*;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.*;

import org.eclipse.osgi.util.NLS;
import org.eclipse.wst.server.core.*;
import org.eclipse.wst.server.core.model.*;
import org.eclipse.wst.server.core.util.SocketUtil;
/**
 * 
 */
public class Server extends Base implements IServer {
	/**
	 * Server id attribute (value "server-id") of launch configurations.
	 * This attribute is used to tag a launch configuration with the
	 * id of the corresponding server.
	 * 
	 * @see ILaunchConfiguration
	 */
	public static final String ATTR_SERVER_ID = "server-id";

	protected static final List EMPTY_LIST = new ArrayList(0);
	
	/**
	 * File extension (value "server") for serialized representation of
	 * server instances.
	 * <p>
	 * [issue: What is relationship between this file extension and
	 * the file passed to IServerType.create(...) or returned by
	 * IServer.getFile()? That is, are server files expected to end
	 * in ".server", or is this just a default? If the former
	 * (as I suspect), then IServerType.create needs to say so,
	 * and the implementation should enforce the restriction.]
	 * </p>
	 */
	public static final String FILE_EXTENSION = "server";
	
	protected static final String PROP_HOSTNAME = "hostname";
	protected static final String SERVER_ID = "server-id";
	protected static final String RUNTIME_ID = "runtime-id";
	protected static final String CONFIGURATION_ID = "configuration-id";
	protected static final String MODULE_LIST = "modules";
	protected static final String PROP_AUTO_PUBLISH_TIME = "auto-publish-time";
	protected static final String PROP_AUTO_PUBLISH_DEFAULT = "auto-publish-default";

	protected static final char[] INVALID_CHARS = new char[] {'\\', '/', ':', '*', '?', '"', '<', '>', '|', '\0', '@', '&'};

	protected IServerType serverType;
	protected ServerDelegate delegate;
	protected ServerBehaviourDelegate behaviourDelegate;

	protected IRuntime runtime;
	protected IFolder configuration;
	
	// the list of modules that are to be published to the server
	protected List modules;
	
	// transient fields
	protected transient String mode = ILaunchManager.RUN_MODE;
	protected transient int serverState = STATE_UNKNOWN;
	protected transient int serverSyncState;
	protected transient boolean serverRestartNeeded;

	protected transient Map moduleState = new HashMap();
	protected transient Map modulePublishState = new HashMap();
	protected transient Map moduleRestartState = new HashMap();
	
	protected transient IStatus serverStatus;
	protected transient Map moduleStatus = new HashMap();

	protected transient ServerPublishInfo publishInfo;
	protected transient AutoPublishThread autoPublishThread;

/*	private static final String[] stateStrings = new String[] {
		"unknown", "starting", "started", "started_debug",
		"stopping", "stopped", "started_unsupported", "started_profile"
	};*/
	
	// publish listeners
	protected transient List publishListeners;
	
	// Server listeners
	protected transient ServerNotificationManager notificationManager;
	
	public class AutoPublishThread extends Thread {
		public boolean stop;
		public int time = 0; 
		
		public void run() {
			Trace.trace(Trace.FINEST, "Auto-publish thread starting for " + Server.this + " - " + time + "s");
			if (stop)
				return;
			
			try {
				sleep(time * 1000);
			} catch (Exception e) {
				// ignore
			}
			
			if (stop)
				return;
			
			Trace.trace(Trace.FINEST, "Auto-publish thread publishing " + Server.this);

			PublishServerJob publishJob = new PublishServerJob(Server.this, IServer.PUBLISH_AUTO, false);
			publishJob.schedule();
		}
	}

	// working copy, loaded resource
	public Server(IFile file) {
		super(file);
		map.put(PROP_HOSTNAME, "localhost");
	}

	// creation (working copy)
	public Server(String id, IFile file, IRuntime runtime, IServerType serverType) {
		super(file, id);
		this.runtime = runtime;
		this.serverType = serverType;
		map.put("server-type-id", serverType.getId());
		map.put(PROP_HOSTNAME, "localhost");
		if (runtime != null && runtime.getRuntimeType() != null) {
			String name = runtime.getRuntimeType().getName();
			map.put(PROP_NAME, name);
		}
		serverState = ((ServerType)serverType).getInitialState();
	}
	
	public IServerType getServerType() {
		return serverType;
	}
	
	public IServerWorkingCopy createWorkingCopy() {
		return new ServerWorkingCopy(this); 
	}

	public boolean isWorkingCopy() {
		return false;
	}
	
	protected void deleteFromMetadata() {
		ResourceManager.getInstance().removeServer(this);
	}
	
	protected void saveToMetadata(IProgressMonitor monitor) {
		super.saveToMetadata(monitor);
		ResourceManager.getInstance().addServer(this);
	}

	/* (non-Javadoc)
	 * @see com.ibm.wtp.server.core.IServer2#getRuntime()
	 */
	public IRuntime getRuntime() {
		return runtime;
	}

	protected String getRuntimeId() {
		return getAttribute(RUNTIME_ID, (String) null);
	}

	/* (non-Javadoc)
	 * @see com.ibm.wtp.server.core.IServer2#getServerConfiguration()
	 */
	public IFolder getServerConfiguration() {
		return configuration;
	}

	protected ServerDelegate getDelegate(IProgressMonitor monitor) {
		if (delegate != null)
			return delegate;
		
		if (serverType != null) {
			synchronized (this) {
				if (delegate == null) {
					Job job = new Job("Load delegate") {
						protected IStatus run(IProgressMonitor monitor2) {
							try {
								long time = System.currentTimeMillis();
								IConfigurationElement element = ((ServerType) serverType).getElement();
								delegate = (ServerDelegate) element.createExecutableExtension("class");
								delegate.initialize(Server.this);
								Trace.trace(Trace.PERFORMANCE, "Server.getDelegate(): <" + (System.currentTimeMillis() - time) + "> " + getServerType().getId());
							} catch (Throwable t) {
								Trace.trace(Trace.SEVERE, "Could not create delegate " + toString(), t);
							}
							return new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, "", null);
						}
					};
					job.schedule();
					try {
						job.join();
					} catch (Exception e) {
						// ignore
					}
				}
			}
		}
		return delegate;
	}
	
	protected ServerBehaviourDelegate getBehaviourDelegate(IProgressMonitor monitor) {
		if (behaviourDelegate != null)
			return behaviourDelegate;
		
		if (serverType != null) {
			synchronized (this) {
				if (behaviourDelegate == null) {
					Job job = new Job("Load delegate") {
						protected IStatus run(IProgressMonitor monitor2) {
							try {
								long time = System.currentTimeMillis();
								IConfigurationElement element = ((ServerType) serverType).getElement();
								behaviourDelegate = (ServerBehaviourDelegate) element.createExecutableExtension("behaviourClass");
								behaviourDelegate.initialize(Server.this);
								Trace.trace(Trace.PERFORMANCE, "Server.getBehaviourDelegate(): <" + (System.currentTimeMillis() - time) + "> " + getServerType().getId());
							} catch (Throwable t) {
								Trace.trace(Trace.SEVERE, "Could not create behaviour delegate " + toString(), t);
							}
							return new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, "", null);
						}
					};
					job.schedule();
					try {
						job.join();
					} catch (Exception e) {
						// ignore
					}
				}
			}
		}
		return behaviourDelegate;
	}

	/**
	 * Returns true if the delegate has been loaded.
	 * 
	 * @return <code>true</code> if the delegate has been loaded, and
	 *    <code>false</code> otherwise
	 */
	public boolean isDelegateLoaded() {
		return delegate != null;
	}
	
	public void dispose() {
		if (delegate != null)
			delegate.dispose();
	}

	public String getHost() {
		return getAttribute(PROP_HOSTNAME, "localhost");
	}
	
	public int getAutoPublishTime() {
		return getAttribute(PROP_AUTO_PUBLISH_TIME, -1);
	}
	
	public boolean getAutoPublishDefault() {
		return getAttribute(PROP_AUTO_PUBLISH_DEFAULT, true);
	}

	/**
	 * Returns the current state of the server. (see SERVER_XXX constants)
	 *
	 * @return int
	 */
	public int getServerState() {
		return serverState;
	}
	
	public String getMode() {
		return mode;
	}

	public void setServerState(int state) {
		if (state == serverState)
			return;

		this.serverState = state;
		fireServerStateChangeEvent();
	}
	
	/**
	 * Add a listener to this server.
	 *
	 * @param listener org.eclipse.wst.server.model.IServerListener
	 */
	public void addServerListener(IServerListener listener) {
		Trace.trace(Trace.LISTENERS, "Adding server listener " + listener + " to " + this);
		getServerNotificationManager().addListener(listener);
	}
	
	/**
	 * Add a listener to this server with the given event mask.
	 *
	 * @param listener org.eclipse.wst.server.model.IServerListener
	 * @param eventMask to limit listening to certain types of events
	 */
	public void addServerListener(IServerListener listener, int eventMask) {
		Trace.trace(Trace.LISTENERS, "Adding server listener " + listener + " to " + this + " with eventMask " + eventMask);
		getServerNotificationManager().addListener(listener, eventMask);
	}

	/**
	 * Remove a listener from this server.
	 *
	 * @param listener org.eclipse.wst.server.model.IServerListener
	 */
	public void removeServerListener(IServerListener listener) {
		Trace.trace(Trace.LISTENERS, "Removing server listener " + listener + " from " + this);
		getServerNotificationManager().removeListener(listener);
	}
	
	/**
	 * Fire a server listener restart state change event.
	 */
	protected void fireRestartStateChangeEvent() {
		Trace.trace(Trace.LISTENERS, "->- Firing server restart change event: " + getName() + " ->-");
	
		if (notificationManager == null || notificationManager.hasListenerEntries())
			return;
	
		notificationManager.broadcastChange(
			new ServerEvent(ServerEvent.SERVER_CHANGE | ServerEvent.RESTART_STATE_CHANGE, this, getServerState(), 
				getServerPublishState(), getServerRestartState()));
	}
	
	/**
	 * Fire a server listener state change event.
	 */
	protected void fireServerStateChangeEvent() {
		Trace.trace(Trace.LISTENERS, "->- Firing server state change event: " + getName() + ", " + getServerState() + " ->-");
	
		if (notificationManager == null || notificationManager.hasListenerEntries())
			return;
	
		notificationManager.broadcastChange(
			new ServerEvent(ServerEvent.SERVER_CHANGE | ServerEvent.STATE_CHANGE, this, getServerState(), 
				getServerPublishState(), getServerRestartState()));
	}

	/**
	 * Fire a server listener module state change event.
	 */
	protected void fireServerModuleStateChangeEvent(IModule[] module) {
		Trace.trace(Trace.LISTENERS, "->- Firing server module state change event: " + getName() + ", " + getServerState() + " ->-");
		
		if (notificationManager == null || notificationManager.hasListenerEntries())
			return;
	
		notificationManager.broadcastChange(
			new ServerEvent(ServerEvent.MODULE_CHANGE | ServerEvent.STATE_CHANGE, this, module, getModuleState(module), 
				getModulePublishState(module), getModuleRestartState(module)));
	}

	public void setMode(String m) {
		this.mode = m;
	}

	public void setModuleState(IModule[] module, int state) {
		Integer in = new Integer(state);
		moduleState.put(getKey(module), in);
		fireServerModuleStateChangeEvent(module);
	}
	
	public void setModulePublishState(IModule[] module, int state) {
		Integer in = new Integer(state);
		modulePublishState.put(getKey(module), in);
		//fireServerModuleStateChangeEvent(module);
	}

	public void setModuleRestartState(IModule[] module, boolean r) {
		Boolean b = new Boolean(r);
		moduleState.put(getKey(module), b);
		//fireServerModuleStateChangeEvent(module);
	}

	protected void handleModuleProjectChange(final IModule module) {
		Trace.trace(Trace.FINEST, "> handleDeployableProjectChange() " + this + " " + module);
		
		class Helper {
			boolean changed;
		}
		final Helper helper = new Helper();
		
		IModuleVisitor visitor = new IModuleVisitor() {
			public boolean visit(IModule[] module2) {
				int size = module2.length;
				IModule m = module2[size - 1];
				if (m.getProject() == null)
					return true;
				
				if (module.equals(m)) {
					IModuleResourceDelta[] delta2 = getPublishedResourceDelta(module2);
					if (delta2.length > 0)
						helper.changed = true;
					
					// TODO
					/*if (deployableDelta[i] == null)
						deployableDelta[i] = moduleProjects[i].getModuleResourceDelta(delta);
					
					if (deployableDelta[i] != null) {
						// updateDeployable(module, deployableDelta[i]);

						ModulePublishInfo control = PublishInfo.getPublishInfo().getPublishControl(Server.this, parents, module);
						if (control.isDirty())
							return true;
	
						control.setDirty(true);
						firePublishStateChange(parents, module);
					}*/
					return true;
				}
				return true;
			}
		};

		visit(visitor, null);
		
		if (!helper.changed)
			return;
		
		// check for auto-publish
		if (autoPublishThread != null) {
			autoPublishThread.stop = true;
			autoPublishThread.interrupt();
			autoPublishThread = null;
		}
		
		int time = 0;
		if (getAutoPublishDefault()) {
			boolean local = SocketUtil.isLocalhost(getHost());
			if (local && ServerPreferences.getInstance().getAutoPublishLocal())
				time = ServerPreferences.getInstance().getAutoPublishLocalTime();
			else if (!local && ServerPreferences.getInstance().getAutoPublishRemote())
				time = ServerPreferences.getInstance().getAutoPublishRemoteTime();
		} else {
			time = getAutoPublishTime();
		}
		
		if (time > 5) {
			autoPublishThread = new AutoPublishThread();
			autoPublishThread.time = time;
			autoPublishThread.setPriority(Thread.MIN_PRIORITY + 1);
			autoPublishThread.start();
		}
		
		//Trace.trace(Trace.FINEST, "< handleDeployableProjectChange()");
	}
	
	private ServerNotificationManager getServerNotificationManager() {
		if (notificationManager == null) {
			notificationManager = new ServerNotificationManager();
		}
		return notificationManager;
	}

	/**
	 * Returns the configuration's sync state.
	 *
	 * @return int
	 */
	public int getServerPublishState() {
		return serverSyncState;
	}

	/**
	 * Sets the configuration sync state.
	 *
	 * @param state int
	 */
	public void setServerPublishState(int state) {
		if (state == serverSyncState)
			return;
		serverSyncState = state;
		//fireConfigurationSyncStateChangeEvent();
	}

	/**
	 * Adds a publish listener to this server.
	 * Has no effect if an identical listener is already registered.
	 *
	 * @param listener the publish listener
	 * @see #removePublishListener(IPublishListener)
	 */
	public void addPublishListener(IPublishListener listener) {
		Trace.trace(Trace.LISTENERS, "Adding publish listener " + listener + " to " + this);

		if (publishListeners == null)
			publishListeners = new ArrayList();
		publishListeners.add(listener);
	}

	/**
	 * Removes a publish listener from this server.
	 * Has no effect if the listener is not registered.
	 *
	 * @param listener the publish listener
	 * @see #addPublishListener(IPublishListener)
	 */
	public void removePublishListener(IPublishListener listener) {
		Trace.trace(Trace.LISTENERS, "Removing publish listener " + listener + " from " + this);

		if (publishListeners != null)
			publishListeners.remove(listener);
	}
	
	/**
	 * Fire a publish start event.
	 */
	private void firePublishStarted() {
		Trace.trace(Trace.FINEST, "->- Firing publish started event ->-");
	
		if (publishListeners == null || publishListeners.isEmpty())
			return;

		int size = publishListeners.size();
		IPublishListener[] srl = new IPublishListener[size];
		publishListeners.toArray(srl);

		for (int i = 0; i < size; i++) {
			Trace.trace(Trace.FINEST, "  Firing publish started event to " + srl[i]);
			try {
				srl[i].publishStarted(this);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing publish started event to " + srl[i], e);
			}
		}

		Trace.trace(Trace.FINEST, "-<- Done firing publish started event -<-");
	}

	/**
	 * Fire a publish stop event.
	 *
	 * @param status publishing status
	 */
	private void firePublishFinished(IStatus status) {
		Trace.trace(Trace.FINEST, "->- Firing publishing finished event: " + status + " ->-");
	
		if (publishListeners == null || publishListeners.isEmpty())
			return;

		int size = publishListeners.size();
		IPublishListener[] srl = new IPublishListener[size];
		publishListeners.toArray(srl);

		for (int i = 0; i < size; i++) {
			Trace.trace(Trace.FINEST, "  Firing publishing finished event to " + srl[i]);
			try {
				srl[i].publishFinished(this, status);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing publishing finished event to " + srl[i], e);
			}
		}

		Trace.trace(Trace.FINEST, "-<- Done firing publishing finished event -<-");
	}

	/**
	 * Fire a publish state change event.
	 */
	protected void firePublishStateChange(IModule[] module) {
		Trace.trace(Trace.FINEST, "->- Firing publish state change event: " + module + " ->-");
	
		if (notificationManager == null || notificationManager.hasListenerEntries())
			return;
	
		notificationManager.broadcastChange(
			new ServerEvent(ServerEvent.MODULE_CHANGE | ServerEvent.PUBLISH_STATE_CHANGE, this, module, getModuleState(module), 
				getModulePublishState(module), getModuleRestartState(module)));
	}

	/**
	 * Returns true if the server is in a state that it can
	 * be published to.
	 *
	 * @return boolean
	 */
	public IStatus canPublish() {
		// can't publish if the server is starting or stopping
		int state = getServerState();
		if (state == STATE_STARTING || state == STATE_STOPPING)
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorPublishStarting, null);
	
		// can't publish if there is no configuration
		if (getServerType() == null || getServerType().hasServerConfiguration() && configuration == null)
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorPublishNoConfiguration, null);
	
		// return true if the configuration can be published
		if (getServerPublishState() != PUBLISH_STATE_NONE)
			return new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, Messages.canPublishOk, null);

		// return true if any modules can be published
		class Temp {
			boolean found = false;
		}
		//final Temp temp = new Temp();
		
		return new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, Messages.canPublishOk, null);
	
		/*IModuleVisitor visitor = new IModuleVisitor() {
			public boolean visit(IModule[] parents, IModule module) {
				if (getModulePublishState(module) != PUBLISH_STATE_NONE) {
					temp.found = true;
					return false;
				}
				return true;
			}
		};
		ServerUtil.visit(this, visitor, null);
		
		return temp.found;*/
	}

	/**
	 * Returns true if the server is in a state that it can
	 * be published to.
	 *
	 * @return boolean
	 */
	public boolean shouldPublish() {
		if (!canPublish().isOK())
			return false;
	
		if (getServerPublishState() != PUBLISH_STATE_NONE)
			return true;
	
		//if (getUnpublishedModules().length > 0)
		return true;
	
		//return false;
	}

	public ServerPublishInfo getServerPublishInfo() {
		if (publishInfo == null) {
			publishInfo = PublishInfo.getInstance().getServerPublishInfo(this);
		}
		return publishInfo;
	}

	/*
	 * Publish to the server using the progress monitor. The result of the
	 * publish operation is returned as an IStatus.
	 */
	public IStatus publish(final int kind, IProgressMonitor monitor) {
		if (getServerType() == null)
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorPublishing, null);

		// check what is out of sync and publish
		if (getServerType().hasServerConfiguration() && configuration == null)
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorNoConfiguration, null);
		
		if (((ServerType)getServerType()).startBeforePublish()) {
			try {
				synchronousStart(ILaunchManager.RUN_MODE, monitor);
			} catch (CoreException ce) {
				Trace.trace(Trace.SEVERE, "Error starting server", ce);
				return ce.getStatus();
			}
		}
		
		IStatus status = null;
		try {
			firePublishStarted();
			status = doPublish(kind, monitor);
		} catch (Exception e) {
			status = null; // TODO
		} finally {
			firePublishFinished(status);
		}
		return status;
	}

	protected IStatus doPublish(int kind, IProgressMonitor monitor) {
		Trace.trace(Trace.FINEST, "-->-- Publishing to server: " + toString() + " -->--");

		try {
			return getBehaviourDelegate(monitor).publish(kind, monitor);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate publish() " + toString(), e);
			return null;
		}
	}
	
	/**
	 * Returns the publish tasks that have been targetted to this server.
	 * These tasks should be run during publishing.
	 * 
	 * @return a possibly empty array of IOptionalTasks
	 */
	public PublishOperation[] getTasks() {
		final List moduleList = new ArrayList();
		
		IModuleVisitor visitor = new IModuleVisitor() {
			public boolean visit(IModule[] module) {
				moduleList.add(module);
				return true;
			}
		};

		visit(visitor, null);
	
		List tasks = new ArrayList();
		
		String serverTypeId = getServerType().getId();
		
		IPublishTask[] publishTasks = ServerPlugin.getPublishTasks();
		if (publishTasks != null) {
			int size = publishTasks.length;
			for (int i = 0; i < size; i++) {
				IPublishTask task = publishTasks[i];
				if (task.supportsType(serverTypeId)) {
					PublishOperation[] tasks2 = task.getTasks(this, moduleList);
					if (tasks2 != null) {
						int size2 = tasks2.length;
						for (int j = 0; j < size2; j++) {
							if (tasks2[j].getKind() == PublishOperation.REQUIRED)
								tasks.add(tasks2[j]);
						}
					}
				}
			}
		}
		
		return (PublishOperation[]) tasks.toArray(new PublishOperation[tasks.size()]);
	}
	
	public List getAllModules() {
		final List moduleList = new ArrayList();
		
		IModuleVisitor visitor = new IModuleVisitor() {
			public boolean visit(IModule[] module) {
				moduleList.add(module);
				return true;
			}
		};

		visit(visitor, null);
		
		return moduleList;
	}

	/*
	 * Returns the module resources that have been published.
	 * 
	 * @see ServerBehaviourDelegate.getPublishedResources(IModule[], IModule)
	 */
	public IModuleResource[] getPublishedResources(IModule[] module) {
		return getServerPublishInfo().getModulePublishInfo(module).getResources();
	}

	/*
	 * Returns the delta of the current module resources that have been
	 * published compared to the current state of the module.
	 * 
	 * @see ServerBehaviourDelegate.getPublishedResourceDelta(IModule[], IModule)
	 */
	public IModuleResourceDelta[] getPublishedResourceDelta(IModule[] module) {
		return getServerPublishInfo().getDelta(module);
	}
	
	/**
	 * @see IServer#getAdapter(Class)
	 */
	public Object getAdapter(Class adapter) {
		//if (delegate != null) {
			ServerDelegate delegate2 = getDelegate(null);
			if (adapter.isInstance(delegate2))
				return delegate2;
		//}
		//if (behaviourDelegate != null) {
			ServerBehaviourDelegate delegate3 = getBehaviourDelegate(null);
			if (adapter.isInstance(delegate3))
				return delegate3;
		//}
		return Platform.getAdapterManager().getAdapter(this, adapter);
	}
	
	/**
	 * @see IServer#loadAdapter(Class, IProgressMonitor)
	 */
	public Object loadAdapter(Class adapter, IProgressMonitor monitor) {
		ServerDelegate delegate2 = getDelegate(monitor);
		if (adapter.isInstance(delegate2))
			return delegate2;

		ServerBehaviourDelegate delegate3 = getBehaviourDelegate(monitor);
		if (adapter.isInstance(delegate3))
			return delegate3;
		
		return Platform.getAdapterManager().getAdapter(this, adapter);
	}

	public String toString() {
		return getName();
	}

	/**
	 * Returns true if the server is in a state that it can
	 * be started, and supports the given mode.
	 *
	 * @param mode2
	 * @return status
	 */
	public IStatus canStart(String mode2) {
		int state = getServerState();
		if (state != STATE_STOPPED && state != STATE_UNKNOWN)
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.canStartErrorState, null);
		
		if (getServerType() == null || !getServerType().supportsLaunchMode(mode2))
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorLaunchMode, null);

		return new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, Messages.canStartOk, null);
	}
	
	public ILaunch getExistingLaunch() {
		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		
		ILaunch[] launches = launchManager.getLaunches();
		int size = launches.length;
		for (int i = 0; i < size; i++) {
			ILaunchConfiguration launchConfig = launches[i].getLaunchConfiguration();
			try {
				if (launchConfig != null) {
					String serverId = launchConfig.getAttribute(SERVER_ID, (String) null);
					if (getId().equals(serverId)) {
						if (!launches[i].isTerminated())
							return launches[i];
					}
				}
			} catch (CoreException e) {
				// ignore
			}
		}
		
		return null;
	}

	public void setupLaunchConfiguration(ILaunchConfigurationWorkingCopy workingCopy, IProgressMonitor monitor) throws CoreException {
		try {
			getBehaviourDelegate(monitor).setupLaunchConfiguration(workingCopy, monitor);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate setLaunchDefaults() " + toString(), e);
		}
	}

	public void importConfiguration(IRuntime runtime2, IProgressMonitor monitor) {
		try {
			getDelegate(monitor).importConfiguration(runtime2, monitor);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate setLaunchDefaults() " + toString(), e);
		}
	}

	/**
	 * Return the launch configuration for this server. If one does not exist, it
	 * will be created if "create" is true, and otherwise will return null.
	 * 
	 * @param create <code>true</code> if a new launch configuration should be
	 *    created if there are none already
	 * @param monitor a progress monitor, or <code>null</code> if progress
	 *    reporting and cancellation are not desired
	 * @return the launch configuration, no <code>null</code> if there was no
	 *    existing launch configuration and <code>create</code> was false
	 * @throws CoreException
	 */
	public ILaunchConfiguration getLaunchConfiguration(boolean create, IProgressMonitor monitor) throws CoreException {
		ILaunchConfigurationType launchConfigType = ((ServerType) getServerType()).getLaunchConfigurationType();
		
		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfiguration[] launchConfigs = null;
		try {
			launchConfigs = launchManager.getLaunchConfigurations(launchConfigType);
		} catch (CoreException e) {
			// ignore
		}
		
		if (launchConfigs != null) {
			int size = launchConfigs.length;
			for (int i = 0; i < size; i++) {
				try {
					String serverId = launchConfigs[i].getAttribute(SERVER_ID, (String) null);
					if (getId().equals(serverId)) {
						ILaunchConfigurationWorkingCopy wc = launchConfigs[i].getWorkingCopy();
						setupLaunchConfiguration(wc, monitor);
						if (wc.isDirty())
							return wc.doSave();
						return launchConfigs[i];
					}
				} catch (CoreException e) {
					Trace.trace(Trace.SEVERE, "Error configuring launch", e);
				}
			}
		}
		
		if (!create)
			return null;
		
		// create a new launch configuration
		String launchName = getValidLaunchConfigurationName(getName());
		launchName = launchManager.generateUniqueLaunchConfigurationNameFrom(launchName); 
		ILaunchConfigurationWorkingCopy wc = launchConfigType.newInstance(null, launchName);
		wc.setAttribute(SERVER_ID, getId());
		setupLaunchConfiguration(wc, monitor);
		return wc.doSave();
	}

	protected String getValidLaunchConfigurationName(String s) {
		if (s == null || s.length() == 0)
			return "1";
		int size = INVALID_CHARS.length;
		for (int i = 0; i < size; i++) {
			s = s.replace(INVALID_CHARS[i], '_');
		}
		return s;
	}

	/**
	 * @see IServer#start(String, IProgressMonitor)
	 */
	public ILaunch start(String mode2, IProgressMonitor monitor) throws CoreException {
		Trace.trace(Trace.FINEST, "Starting server: " + toString() + ", launchMode: " + mode2);
	
		try {
			ILaunchConfiguration launchConfig = getLaunchConfiguration(true, monitor);
			ILaunch launch = launchConfig.launch(mode2, monitor);
			Trace.trace(Trace.FINEST, "Launch: " + launch);
			return launch;
		} catch (CoreException e) {
			Trace.trace(Trace.SEVERE, "Error starting server " + toString(), e);
			throw e;
		}
	}

	/**
	 * Clean up any old launch configurations with the current server's id.
	 */
	protected void deleteLaunchConfigurations() {
		if (getServerType() == null)
			return;
		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationType launchConfigType = ((ServerType) getServerType()).getLaunchConfigurationType();
		
		ILaunchConfiguration[] configs = null;
		try {
			configs = launchManager.getLaunchConfigurations(launchConfigType);
			int size = configs.length;
			for (int i = 0; i < size; i++) {
				try {
					if (getId().equals(configs[i].getAttribute(SERVER_ID, (String) null)))
						configs[i].delete();
				} catch (Exception e) {
					// ignore
				}
			}
		} catch (Exception e) {
			// ignore
		}
	}

	/**
	 * @see IServer#canRestart(String)
	 */
	public IStatus canRestart(String mode2) {
		if (!getServerType().supportsLaunchMode(mode2))
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorLaunchMode, null);

		int state = getServerState();
		if (state == STATE_STARTED)
			return new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, Messages.canRestartOk, null);
		
		return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorRestartNotStarted, null);
	}

	/**
	 * Returns the current restart state of the server. This
	 * implementation will always return false when the server
	 * is stopped.
	 *
	 * @return boolean
	 */
	public boolean getServerRestartState() {
		if (getServerState() == STATE_STOPPED)
			return false;
		return serverRestartNeeded;
	}

	/**
	 * Sets the server restart state.
	 *
	 * @param state boolean
	 */
	public synchronized void setServerRestartState(boolean state) {
		if (state == serverRestartNeeded)
			return;
		serverRestartNeeded = state;
		fireRestartStateChangeEvent();
	}

	/**
	 * @see IServer#restart(String)
	 */
	public void restart(final String mode2) {
		if (getServerState() == STATE_STOPPED)
			return;
	
		Trace.trace(Trace.FINEST, "Restarting server: " + getName());
	
		try {
			try {
				getBehaviourDelegate(null).restart(mode2);
				return;
			} catch (CoreException ce) {
				Trace.trace(Trace.SEVERE, "Error calling delegate restart() " + toString());
			}
		
			// add listener to start it as soon as it is stopped
			addServerListener(new IServerListener() {
				public void serverChanged(ServerEvent event) {
					int eventKind = event.getKind();
					IServer server = event.getServer();
					if (eventKind == (ServerEvent.SERVER_CHANGE | ServerEvent.STATE_CHANGE)) {
						if (server.getServerState() == STATE_STOPPED) {
							server.removeServerListener(this);

							// restart in a quarter second (give other listeners a chance
							// to hear the stopped message)
							Thread t = new Thread() {
								public void run() {
									try {
										Thread.sleep(250);
									} catch (Exception e) {
										// ignore
									}
									try {
										Server.this.start(mode2, new NullProgressMonitor());
									} catch (Exception e) {
										Trace.trace(Trace.SEVERE, "Error while restarting server", e);
									}
								}
							};
							t.setDaemon(true);
							t.setPriority(Thread.NORM_PRIORITY - 2);
							t.start();
						}
					}
					
				}
			});
	
			// stop the server
			stop(false);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error restarting server", e);
		}
	}


	/**
	 * Returns true if the server is in a state that it can
	 * be stopped.
	 *
	 * @return boolean
	 */
	public IStatus canStop() {
		if (getServerState() == STATE_STOPPED)
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorStopAlreadyStopped, null);

		return new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, Messages.canStopOk, null);
	}

	/**
	 * @see IServer#stop(boolean)
	 */
	public void stop(boolean force) {
		if (getServerState() == STATE_STOPPED)
			return;

		Trace.trace(Trace.FINEST, "Stopping server: " + toString());

		try {
			getBehaviourDelegate(null).stop(force);
		} catch (Throwable t) {
			Trace.trace(Trace.SEVERE, "Error calling delegate stop() " + toString(), t);
		}
	}

	/**
	 * @see IServer#synchronousStart(String, IProgressMonitor)
	 */
	public ILaunch synchronousStart(String mode2, IProgressMonitor monitor) throws CoreException {
		Trace.trace(Trace.FINEST, "synchronousStart 1");
		final Object mutex = new Object();
	
		// add listener to the server
		IServerListener listener = new IServerListener() {
			public void serverChanged(ServerEvent event) {
				int eventKind = event.getKind();
				IServer server = event.getServer();
				if (eventKind == (ServerEvent.SERVER_CHANGE | ServerEvent.STATE_CHANGE)) {
					int state = server.getServerState();
					if (state == IServer.STATE_STARTED || state == IServer.STATE_STOPPED) {
						// notify waiter
						synchronized (mutex) {
							try {
								Trace.trace(Trace.FINEST, "synchronousStart notify");
								mutex.notifyAll();
							} catch (Exception e) {
								Trace.trace(Trace.SEVERE, "Error notifying server start", e);
							}
						}
					}
				}
			}
		};
		addServerListener(listener);
		
		final int serverTimeout = ((ServerType) getServerType()).getStartTimeout();
		class Timer {
			boolean timeout;
			boolean alreadyDone;
		}
		final Timer timer = new Timer();
		
		Thread thread = new Thread() {
			public void run() {
				try {
					Thread.sleep(serverTimeout * 1000);
					if (!timer.alreadyDone) {
						timer.timeout = true;
						// notify waiter
						synchronized (mutex) {
							Trace.trace(Trace.FINEST, "synchronousStart notify timeout");
							mutex.notifyAll();
						}
					}
				} catch (Exception e) {
					Trace.trace(Trace.SEVERE, "Error notifying server start timeout", e);
				}
			}
		};
		thread.setDaemon(true);
		thread.start();
	
		Trace.trace(Trace.FINEST, "synchronousStart 2");
	
		// start the server
		ILaunch launch;
		try {
			launch = start(mode2, monitor);
		} catch (CoreException e) {
			removeServerListener(listener);
			timer.alreadyDone = true;
			throw e;
		}
	
		Trace.trace(Trace.FINEST, "synchronousStart 3");
	
		// wait for it! wait for it! ...
		synchronized (mutex) {
			try {
				while (!timer.timeout && !(getServerState() == IServer.STATE_STARTED || getServerState() == IServer.STATE_STOPPED))
					mutex.wait();
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "Error waiting for server start", e);
			}
		}
		removeServerListener(listener);
		timer.alreadyDone = true;
		
		if (timer.timeout)
			throw new CoreException(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, NLS.bind(Messages.errorStartTimeout, new String[] { getName(), serverTimeout + "" }), null));
		timer.alreadyDone = true;
		
		if (getServerState() == IServer.STATE_STOPPED)
			throw new CoreException(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, NLS.bind(Messages.errorStartFailed, getName()), null));
	
		Trace.trace(Trace.FINEST, "synchronousStart 4");
		
		return launch;
	}

	/*
	 * @see IServer#synchronousRestart(String, IProgressMonitor)
	 */
	public void synchronousRestart(String mode2, IProgressMonitor monitor) throws CoreException {
		synchronousStop(true);
		synchronousStart(mode2, monitor);
	}

	/*
	 * @see IServer#synchronousStop()
	 */
	public void synchronousStop(boolean force) {
		if (getServerState() == IServer.STATE_STOPPED)
			return;
		
		final Object mutex = new Object();
	
		// add listener to the server
		IServerListener listener = new IServerListener() {
			public void serverChanged(ServerEvent event) {
				int eventKind = event.getKind();
				IServer server = event.getServer();
				if (eventKind == (ServerEvent.SERVER_CHANGE | ServerEvent.STATE_CHANGE)) {
					int state = server.getServerState();
					if (Server.this == server && state == IServer.STATE_STOPPED) {
						// notify waiter
						synchronized (mutex) {
							try {
								mutex.notifyAll();
							} catch (Exception e) {
								Trace.trace(Trace.SEVERE, "Error notifying server stop", e);
							}
						}
					}
				}
			}
		};
		addServerListener(listener);
		
		class Timer {
			boolean timeout;
			boolean alreadyDone;
		}
		final Timer timer = new Timer();
		
		Thread thread = new Thread() {
			public void run() {
				try {
					Thread.sleep(120000);
					if (!timer.alreadyDone) {
						timer.timeout = true;
						// notify waiter
						synchronized (mutex) {
							Trace.trace(Trace.FINEST, "stop notify timeout");
							mutex.notifyAll();
						}
					}
				} catch (Exception e) {
					Trace.trace(Trace.SEVERE, "Error notifying server stop timeout", e);
				}
			}
		};
		thread.setDaemon(true);
		thread.start();
	
		// stop the server
		stop(force);
	
		// wait for it! wait for it!
		synchronized (mutex) {
			try {
				while (!timer.timeout && getServerState() != IServer.STATE_STOPPED)
					mutex.wait();
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "Error waiting for server stop", e);
			}
		}
		removeServerListener(listener);
		
		/*
		//can't throw exceptions
		if (timer.timeout)
			throw new CoreException(new Status(IStatus.ERROR, ServerCore.PLUGIN_ID, 0, ServerPlugin.getResource("%errorStartFailed", getName()), null));
		else
			timer.alreadyDone = true;
		
		if (getServerState() == IServer.STATE_STOPPED)
			throw new CoreException(new Status(IStatus.ERROR, ServerCore.PLUGIN_ID, 0, ServerPlugin.getResource("%errorStartFailed", getName()), null));*/
	}
	
	/**
	 * Trigger a restart of the given module and wait until it has finished restarting.
	 *
	 * @param module org.eclipse.wst.server.core.IModule
	 * @param monitor org.eclipse.core.runtime.IProgressMonitor
	 * @exception org.eclipse.core.runtime.CoreException - thrown if an error occurs while trying to restart the module
	 */
	public void synchronousRestartModule(final IModule[] module, IProgressMonitor monitor) throws CoreException {
		Trace.trace(Trace.FINEST, "synchronousModuleRestart 1");

		final Object mutex = new Object();
	
		// add listener to the module
		IServerListener listener = new IServerListener() {
			public void serverChanged(ServerEvent event) {
				int eventKind = event.getKind();
				IServer server = event.getServer();
				if (eventKind == (ServerEvent.MODULE_CHANGE | ServerEvent.STATE_CHANGE)) {
					int state = server.getModuleState(module);
					if (state == IServer.STATE_STARTED || state == IServer.STATE_STOPPED) {
						// notify waiter
						synchronized (mutex) {
							try {
								Trace.trace(Trace.FINEST, "synchronousModuleRestart notify");
								mutex.notifyAll();
							} catch (Exception e) {
								Trace.trace(Trace.SEVERE, "Error notifying module restart", e);
							}
						}
					}
				}
			}
		};
		addServerListener(listener);
		
		// make sure it times out after 30s
		class Timer {
			boolean timeout;
			boolean alreadyDone;
		}
		final Timer timer = new Timer();
		
		Thread thread = new Thread() {
			public void run() {
				try {
					Thread.sleep(30000);
					if (!timer.alreadyDone) {
						timer.timeout = true;
						// notify waiter
						synchronized (mutex) {
							Trace.trace(Trace.FINEST, "synchronousModuleRestart notify timeout");
							mutex.notifyAll();
						}
					}
				} catch (Exception e) {
					Trace.trace(Trace.SEVERE, "Error notifying module restart timeout", e);
				}
			}
		};
		thread.setDaemon(true);
		thread.start();
	
		Trace.trace(Trace.FINEST, "synchronousModuleRestart 2");
	
		// restart the module
		try {
			getBehaviourDelegate(null).restartModule(module, monitor);
		} catch (CoreException e) {
			removeServerListener(listener);
			throw e;
		}
	
		Trace.trace(Trace.FINEST, "synchronousModuleRestart 3");
	
		// wait for it! wait for it! ...
		synchronized (mutex) {
			try {
				while (!timer.timeout && !(getModuleState(module) == IServer.STATE_STARTED || getModuleState(module) == IServer.STATE_STOPPED))
					mutex.wait();
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "Error waiting for server start", e);
			}
		}
		removeServerListener(listener);
		if (timer.timeout)
			throw new CoreException(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, NLS.bind(Messages.errorModuleRestartFailed, getName()), null));
		timer.alreadyDone = true;
		
		if (getModuleState(module) == IServer.STATE_STOPPED)
			throw new CoreException(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, NLS.bind(Messages.errorModuleRestartFailed, getName()), null));
	
		Trace.trace(Trace.FINEST, "synchronousModuleRestart 4");
	}

	public IPath getTempDirectory() {
		return ServerPlugin.getInstance().getTempDirectory(getId());
	}

	protected String getXMLRoot() {
		return "server";
	}
	
	protected void loadState(IMemento memento) {
		/*String serverTypeId = memento.getString("server-type-id");
		serverType = ServerCore.getServerType(serverTypeId);
		
		String runtimeId = memento.getString("runtime-id");
		runtime = ServerCore.getResourceManager().getRuntime(runtimeId);
		
		String configurationId = memento.getString("configuration-id");
		configuration = ServerCore.getResourceManager().getServerConfiguration(configurationId);*/
		resolve();
	}
	
	protected void resolve() {
		IServerType oldServerType = serverType;
		String serverTypeId = getAttribute("server-type-id", (String)null);
		if (serverTypeId != null)
			serverType = ServerCore.findServerType(serverTypeId);
		else
			serverType = null;
		if (serverType != null && !serverType.equals(oldServerType))
			serverState = ((ServerType)serverType).getInitialState();
		
		String runtimeId = getAttribute(RUNTIME_ID, (String)null);
		if (runtimeId != null)
			runtime = ServerCore.findRuntime(runtimeId);
		
		String configPath = getAttribute(CONFIGURATION_ID, (String)null);
		configuration = null;
		if (configPath != null)
			configuration = ResourcesPlugin.getWorkspace().getRoot().getFolder(new Path(configPath));
	}

	protected void setInternal(ServerWorkingCopy wc) {
		map = wc.map;
		configuration = wc.configuration;
		runtime = wc.runtime;
		serverSyncState = wc.serverSyncState;
		//restartNeeded = wc.restartNeeded;
		serverType = wc.serverType;
		modules = wc.modules;

		// can never modify the following properties via the working copy
		//serverState = wc.serverState;
		delegate = wc.delegate;
	}

	protected void saveState(IMemento memento) {
		if (serverType != null)
			memento.putString("server-type", serverType.getId());

		if (configuration != null)
			memento.putString(CONFIGURATION_ID, configuration.getFullPath().toString());
		else
			memento.putString(CONFIGURATION_ID, null);
		
		if (runtime != null)
			memento.putString(RUNTIME_ID, runtime.getId());
		else
			memento.putString(RUNTIME_ID, null);
	}

	/*public void updateConfiguration() {
		try {
			getDelegate(null).updateConfiguration();
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate updateConfiguration() " + toString(), e);
		}
	}*/
	
	/* (non-Javadoc)
	 * @see org.eclipse.wst.server.core.IServerConfiguration#canModifyModule(org.eclipse.wst.server.core.model.IModule)
	 */
	public IStatus canModifyModules(IModule[] add, IModule[] remove, IProgressMonitor monitor) {
		try {
			return getDelegate(monitor).canModifyModules(add, remove);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate canModifyModules() " + toString(), e);
			return null;
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.wst.server.core.IServer#getModules()
	 */
	public IModule[] getModules() {
		if (modules == null) {
			// convert from attribute
			List list = getAttribute(MODULE_LIST, (List) null);
			if (list == null)
				list = new ArrayList(1);
			
			modules = new ArrayList(list.size() + 1);
			Iterator iterator = list.iterator();
			while (iterator.hasNext()) {
				String moduleStr = (String) iterator.next();
				IModule module = ServerUtil.getModule(moduleStr);
				if (module != null)
					modules.add(module);
			}
		} else {
			// verify modules are still available
			List remove = new ArrayList();
			Iterator iterator = modules.iterator();
			while (iterator.hasNext()) {
				IModule module = (IModule) iterator.next();
				if (ServerUtil.getModule(module.getId()) == null)
					remove.add(module);
			}
			
			iterator = remove.iterator();
			while (iterator.hasNext()) {
				modules.remove(iterator.next());
			}
		}
		
		IModule[] modules2 = new IModule[modules.size()];
		modules.toArray(modules2);
		return modules2;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.wst.server.core.IServer#getModuleState()
	 */
	public int getModuleState(IModule[] module) {
		try {
			Integer in = (Integer) moduleState.get(getKey(module));
			if (in != null)
				return in.intValue();
		} catch (Exception e) {
			// ignore
		}
		return STATE_UNKNOWN;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.wst.server.core.IServer#getModuleState()
	 */
	public int getModulePublishState(IModule[] module) {
		try {
			Integer in = (Integer) modulePublishState.get(getKey(module));
			if (in != null)
				return in.intValue();
		} catch (Exception e) {
			// ignore
		}
		return PUBLISH_STATE_UNKNOWN;
	}

	/*
	 * @see IServer#getChildModule(IModule[])
	 */
	public IModule[] getChildModules(IModule[] module, IProgressMonitor monitor) {
		try {
			return getDelegate(monitor).getChildModules(module);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate getChildModules() " + toString(), e);
			return null;
		}
	}

	/*
	 * @see IServer#getRootModules(IModule)
	 */
	public IModule[] getRootModules(IModule module, IProgressMonitor monitor) throws CoreException {
		try {
			return getDelegate(monitor).getRootModules(module);
		} catch (CoreException se) {
			//Trace.trace(Trace.FINER, "CoreException calling delegate getParentModules() " + toString() + ": " + se.getMessage());
			throw se;
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate getParentModules() " + toString(), e);
			return null;
		}
	}
	
	/*
	 * 
	 */
	/*public boolean hasRuntime() {
		try {
			return getDelegate().requiresRuntime();
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate requiresRuntime() " + toString(), e);
			return false;
		}
	}*/
	
	/**
	 * Returns whether the given module can be restarted.
	 *
	 * @param module the module
	 * @param monitor
	 * @return <code>true</code> if the given module can be
	 *    restarted, and <code>false</code> otherwise
	 */
	public IStatus canRestartModule(IModule[] module, IProgressMonitor monitor) {
		try {
			boolean b = getBehaviourDelegate(monitor).canRestartModule(module);
			if (b)
				return new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, Messages.canRestartModuleOk, null);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate canRestartRuntime() " + toString(), e);
		}
		return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, Messages.errorRestartModule, null);
	}

	/**
	 * Check if the given module is in sync on the server. It should
	 * return true if the module should be restarted (is out of
	 * sync) or false if the module does not need to be restarted.
	 *
	 * @param module org.eclipse.wst.server.core.model.IModule
	 * @return boolean
	 */
	public boolean getModuleRestartState(IModule[] module) {
		try {
			Boolean b = (Boolean) moduleRestartState.get(getKey(module));
			if (b != null)
				return b.booleanValue();
		} catch (Exception e) {
			// ignore
		}
		return false;
	}

	/*
	 * @see IServer#restartModule(IModule[], IProgressMonitor)
	 */
	public void restartModule(IModule[] module, IProgressMonitor monitor) throws CoreException {
		try {
			getBehaviourDelegate(monitor).restartModule(module, monitor);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate restartModule() " + toString(), e);
		}
	}
	
	/**
	 * Returns an array of IServerPorts that this server has.
	 *
	 * @param monitor
	 * @return a possibly empty array of servers ports
	 */
	public ServerPort[] getServerPorts(IProgressMonitor monitor) {
		try {
			return getDelegate(monitor).getServerPorts();
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate getServerPorts() " + toString(), e);
			return null;
		}
	}
	
	/**
	 * Visit all the modules in the server with the given module visitor.
	 * 
	 * @param visitor the visitor
	 * @param monitor a progress monitor, or <code>null</code> if progress
	 *    reporting and cancellation are not desired
	 */
	public void visit(IModuleVisitor visitor, IProgressMonitor monitor) {
		IModule[] modules2 = getModules();
		if (modules2 != null) { 
			int size = modules2.length;
			for (int i = 0; i < size; i++) {
				if (!visitModule(new IModule[] { modules2[i] }, visitor, monitor))
					return;
			}
		}
	}

	/**
	 * Returns true to keep visiting, and false to stop.
	 * 
	 * @param monitor a progress monitor, or <code>null</code> if progress
	 *    reporting and cancellation are not desired
	 */
	private boolean visitModule(IModule[] module, IModuleVisitor visitor, IProgressMonitor monitor) {
		if (module == null)
			return true;
		
		if (!visitor.visit(module))
			return false;
		
		IModule[] children = getChildModules(module, monitor);
		if (children != null) {
			int size = children.length;
			for (int i = 0; i < size; i++) {
				IModule[] module2 = new IModule[module.length + 1];
				System.arraycopy(module, 0, module2, 0, module.length);
				module2[module.length] = children[i];
				
				if (!visitModule(module2, visitor, monitor))
					return false;
			}
		}
		
		return true;
	}
	
	private String getKey(IModule[] module) {
		StringBuffer sb = new StringBuffer();
		
		if (module != null) {
			int size = module.length;
			for (int i = 0; i < size; i++) {
				if (i != 0)
					sb.append("#");
				sb.append(module[i].getId());
			}
		}
		
		return sb.toString();
	}
	
	public void setModuleStatus(IModule[] module, IStatus status) {
		moduleStatus.put(getKey(module), status);
		//fireServerModuleStateChangeEvent(module);
	}
	
	public IStatus getModuleStatus(IModule[] module) {
		try {
			return (IStatus) moduleStatus.get(getKey(module));
		} catch (Exception e) {
			return null;
		}
	}
	
	public void setServerStatus(IStatus status) {
		serverStatus = status;
		//fireServerStateChangeEvent();
	}
	
	public IStatus getServerStatus() {
		return serverStatus;
	}
}