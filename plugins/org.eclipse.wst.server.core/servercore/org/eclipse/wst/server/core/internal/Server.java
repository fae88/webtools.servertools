/**********************************************************************
 * Copyright (c) 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 *
 * Contributors:
 *     IBM Corporation - Initial API and implementation
 **********************************************************************/
package org.eclipse.wst.server.core.internal;

import java.util.*;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.*;
import org.eclipse.debug.core.*;
import org.osgi.framework.Bundle;

import org.eclipse.wst.server.core.*;
import org.eclipse.wst.server.core.model.*;
import org.eclipse.wst.server.core.util.ServerAdapter;
/**
 * 
 */
public class Server extends Base implements IServer {
	protected static final List EMPTY_LIST = new ArrayList(0);
	
	protected static final String PROP_HOSTNAME = "hostname";
	protected static final String SERVER_ID = "server-id";
	protected static final String RUNTIME_ID = "runtime-id";
	protected static final String CONFIGURATION_ID = "configuration-id";

	protected IServerType serverType;
	protected ServerDelegate delegate;
	protected ServerBehaviourDelegate behaviourDelegate;

	protected IRuntime runtime;
	protected IServerConfiguration configuration;
	protected String mode;

	protected int serverState = STATE_UNKNOWN;
	protected int serverSyncState;
	protected boolean serverRestartNeeded;
	
	protected Map moduleState = new HashMap();
	protected Map modulePublishState = new HashMap();
	protected Map moduleRestartState = new HashMap();

/*	private static final String[] stateStrings = new String[] {
		"unknown", "starting", "started", "started_debug",
		"stopping", "stopped", "started_unsupported", "started_profile"
	};*/
	
	// publish listeners
	protected transient List publishListeners;
	
	// server listeners
	protected transient List serverListeners;

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
	public IServerConfiguration getServerConfiguration() {
		return configuration;
	}

	protected ServerDelegate getDelegate() {
		if (delegate != null)
			return delegate;
		
		if (serverType != null) {
			synchronized (this) {
				if (delegate == null) {
					try {
						long time = System.currentTimeMillis();
						IConfigurationElement element = ((ServerType) serverType).getElement();
						delegate = (ServerDelegate) element.createExecutableExtension("class");
						delegate.initialize(this);
						Trace.trace(Trace.PERFORMANCE, "Server.getDelegate(): <" + (System.currentTimeMillis() - time) + "> " + getServerType().getId());
					} catch (Exception e) {
						Trace.trace(Trace.SEVERE, "Could not create delegate " + toString(), e);
					}
				}
			}
		}
		return delegate;
	}
	
	protected ServerBehaviourDelegate getBehaviourDelegate() {
		if (behaviourDelegate != null)
			return behaviourDelegate;
		
		if (serverType != null) {
			synchronized (this) {
				if (behaviourDelegate == null) {
					try {
						long time = System.currentTimeMillis();
						IConfigurationElement element = ((ServerType) serverType).getElement();
						behaviourDelegate = (ServerBehaviourDelegate) element.createExecutableExtension("behaviourClass");
						behaviourDelegate.initialize(this);
						Trace.trace(Trace.PERFORMANCE, "Server.getDelegate(): <" + (System.currentTimeMillis() - time) + "> " + getServerType().getId());
					} catch (Exception e) {
						Trace.trace(Trace.SEVERE, "Could not create delegate " + toString(), e);
					}
				}
			}
		}
		return behaviourDelegate;
	}

	/**
	 * Returns true if the delegate has been loaded.
	 * 
	 * @return
	 */
	public boolean isDelegateLoaded() {
		return delegate != null;
	}
	
	public void dispose() {
		if (delegate != null)
			delegate.dispose();
	}
	
	public boolean isDelegatePluginActivated() {
		IConfigurationElement element = ((ServerType) serverType).getElement();
		String pluginId = element.getDeclaringExtension().getNamespace();
		return Platform.getBundle(pluginId).getState() == Bundle.ACTIVE;
	}
	
	/**
	 * Returns true if this is a configuration that is
	 * applicable to (can be used with) this server.
	 *
	 * @param configuration org.eclipse.wst.server.core.model.IServerConfiguration
	 * @return boolean
	 */
	public boolean isSupportedConfiguration(IServerConfiguration configuration2) {
		if (!getServerType().hasServerConfiguration() || configuration2 == null)
			return false;
		return getServerType().getServerConfigurationType().equals(configuration2.getServerConfigurationType());
	}

	public String getHost() {
		return getAttribute(PROP_HOSTNAME, "localhost");
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
	
		if (serverListeners == null)
			serverListeners = new ArrayList();
		serverListeners.add(listener);
	}
	
	/**
	 * Remove a listener from this server.
	 *
	 * @param listener org.eclipse.wst.server.model.IServerListener
	 */
	public void removeServerListener(IServerListener listener) {
		Trace.trace(Trace.LISTENERS, "Removing server listener " + listener + " from " + this);
	
		if (serverListeners != null)
			serverListeners.remove(listener);
	}
	
	/**
	 * Fire a server listener configuration sync state change event.
	 */
	protected void fireConfigurationSyncStateChangeEvent() {
		Trace.trace(Trace.LISTENERS, "->- Firing server configuration change event: " + getName() + " ->-");
	
		if (serverListeners == null || serverListeners.isEmpty())
			return;
	
		int size = serverListeners.size();
		IServerListener[] sil = new IServerListener[size];
		serverListeners.toArray(sil);
	
		for (int i = 0; i < size; i++) {
			try {
				Trace.trace(Trace.LISTENERS, "  Firing server configuration change event to: " + sil[i]);
				sil[i].configurationSyncStateChange(this);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing server configuration change event", e);
			}
		}
		Trace.trace(Trace.LISTENERS, "-<- Done firing server configuration change event -<-");
	}
	
	/**
	 * Fire a server listener restart state change event.
	 */
	protected void fireRestartStateChangeEvent() {
		Trace.trace(Trace.LISTENERS, "->- Firing server restart change event: " + getName() + " ->-");
	
		if (serverListeners == null || serverListeners.isEmpty())
			return;
	
		int size = serverListeners.size();
		IServerListener[] sil = new IServerListener[size];
		serverListeners.toArray(sil);
	
		for (int i = 0; i < size; i++) {
			try {
				Trace.trace(Trace.LISTENERS, "  Firing server restart change event to: " + sil[i]);
				sil[i].restartStateChange(this);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing server restart change event", e);
			}
		}
		Trace.trace(Trace.LISTENERS, "-<- Done firing server restart change event -<-");
	}
	
	/**
	 * Fire a server listener state change event.
	 */
	protected void fireServerStateChangeEvent() {
		Trace.trace(Trace.LISTENERS, "->- Firing server state change event: " + getName() + ", " + getServerState() + " ->-");
	
		if (serverListeners == null || serverListeners.isEmpty())
			return;
	
		int size = serverListeners.size();
		IServerListener[] sil = new IServerListener[size];
		serverListeners.toArray(sil);
	
		for (int i = 0; i < size; i++) {
			try {
				Trace.trace(Trace.LISTENERS, "  Firing server state change event to: " + sil[i]);
				sil[i].serverStateChange(this);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing server state change event", e);
			}
		}
		Trace.trace(Trace.LISTENERS, "-<- Done firing server state change event -<-");
	}
	
	/**
	 * Fire a server listener module change event.
	 */
	protected void fireServerModuleChangeEvent() {
		Trace.trace(Trace.LISTENERS, "->- Firing server module change event: " + getName() + ", " + getServerState() + " ->-");
		
		if (serverListeners == null || serverListeners.isEmpty())
			return;
		
		int size = serverListeners.size();
		IServerListener[] sil = new IServerListener[size];
		serverListeners.toArray(sil);
		
		for (int i = 0; i < size; i++) {
			try {
				Trace.trace(Trace.LISTENERS, "  Firing server module change event to: " + sil[i]);
				sil[i].modulesChanged(this);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing server module change event", e);
			}
		}
		Trace.trace(Trace.LISTENERS, "-<- Done firing server module change event -<-");
	}

	/**
	 * Fire a server listener module state change event.
	 */
	protected void fireServerModuleStateChangeEvent(IModule module) {
		Trace.trace(Trace.LISTENERS, "->- Firing server module state change event: " + getName() + ", " + getServerState() + " ->-");
		
		if (serverListeners == null || serverListeners.isEmpty())
			return;
		
		int size = serverListeners.size();
		IServerListener[] sil = new IServerListener[size];
		serverListeners.toArray(sil);
		
		for (int i = 0; i < size; i++) {
			try {
				Trace.trace(Trace.LISTENERS, "  Firing server module state change event to: " + sil[i]);
				sil[i].moduleStateChange(this, module);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing server module state change event", e);
			}
		}
		Trace.trace(Trace.LISTENERS, "-<- Done firing server module state change event -<-");
	}
	
	public void setMode(String m) {
		this.mode = m;
	}

	public void setModuleState(IModule module, int state) {
		Integer in = new Integer(state);
		moduleState.put(module.getId(), in);
		fireServerModuleStateChangeEvent(module);
	}
	
	public void setModulePublishState(IModule module, int state) {
		Integer in = new Integer(state);
		modulePublishState.put(module.getId(), in);
		//fireServerModuleStateChangeEvent(module);
	}

	public void setModuleRestartState(IModule module, boolean r) {
		Boolean b = new Boolean(r);
		moduleState.put(module.getId(), b);
		//fireServerModuleStateChangeEvent(module);
	}

	protected void handleModuleProjectChange(final IResourceDelta delta, final IModule[] moduleProjects) {
		//Trace.trace(Trace.FINEST, "> handleDeployableProjectChange() " + server + " " + delta + " " + moduleProjects);
		final int size = moduleProjects.length;
		//final IModuleResourceDelta[] deployableDelta = new IModuleResourceDelta[size];
		// TODO
		IModuleVisitor visitor = new IModuleVisitor() {
			public boolean visit(IModule[] parents, IModule module) {
				if (module.getProject() == null)
					return true;
				
				for (int i = 0; i < size; i++) {
					if (moduleProjects[i].equals(module)) {
						/*if (deployableDelta[i] == null)
							deployableDelta[i] = moduleProjects[i].getModuleResourceDelta(delta);
						
						if (deployableDelta[i] != null) {
							// updateDeployable(module, deployableDelta[i]);

							PublishControl control = PublishInfo.getPublishInfo().getPublishControl(Server.this, parents, module);
							if (control.isDirty())
								return true;
		
							control.setDirty(true);
							firePublishStateChange(parents, module);
						}*/
						return true;
					}
				}
				return true;
			}
		};

		ServerUtil.visit(this, visitor, null);
		//Trace.trace(Trace.FINEST, "< handleDeployableProjectChange()");
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
		fireConfigurationSyncStateChangeEvent();
	}

	/**
	 * Adds a publish listener to this server.
	 *
	 * @param listener org.eclipse.wst.server.core.model.IPublishListener
	 */
	public void addPublishListener(IPublishListener listener) {
		Trace.trace(Trace.LISTENERS, "Adding publish listener " + listener + " to " + this);

		if (publishListeners == null)
			publishListeners = new ArrayList();
		publishListeners.add(listener);
	}

	/**
	 * Removes a publish listener from this server.
	 *
	 * @param listener org.eclipse.wst.server.core.model.IPublishListener
	 */
	public void removePublishListener(IPublishListener listener) {
		Trace.trace(Trace.LISTENERS, "Removing publish listener " + listener + " from " + this);

		if (publishListeners != null)
			publishListeners.remove(listener);
	}
	
	/**
	 * Fire a publish start event.
	 *
	 * @param 
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
	 * Fire a publish target event.
	 *
	 * @param 
	 */
	private void fireModulePublishStarted(IModule[] parents, IModule module) {
		Trace.trace(Trace.FINEST, "->- Firing module publish started event: " + module + " ->-");
	
		if (publishListeners == null || publishListeners.isEmpty())
			return;

		int size = publishListeners.size();
		IPublishListener[] srl = new IPublishListener[size];
		publishListeners.toArray(srl);

		for (int i = 0; i < size; i++) {
			Trace.trace(Trace.FINEST, "  Firing module publish started event to " + srl[i]);
			try {
				srl[i].publishModuleStarted(this, parents, module);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing module publish started event to " + srl[i], e);
			}
		}

		Trace.trace(Trace.FINEST, "-<- Done firing module publish started event -<-");
	}
	
	/**
	 * Fire a publish target event.
	 *
	 * @param 
	 */
	private void fireModulePublishFinished(IModule[] parents, IModule module, IStatus status) {
		Trace.trace(Trace.FINEST, "->- Firing module finished event: " + module + " " + status + " ->-");
	
		if (publishListeners == null || publishListeners.isEmpty())
			return;

		int size = publishListeners.size();
		IPublishListener[] srl = new IPublishListener[size];
		publishListeners.toArray(srl);

		for (int i = 0; i < size; i++) {
			Trace.trace(Trace.FINEST, "  Firing module finished event to " + srl[i]);
			try {
				srl[i].publishModuleFinished(this, parents, module, status);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing module finished event to " + srl[i], e);
			}
		}

		Trace.trace(Trace.FINEST, "-<- Done firing module finished event -<-");
	}
	
	/**
	 * Fire a publish stop event.
	 *
	 * @param 
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
	 *
	 * @param 
	 */
	protected void firePublishStateChange(IModule[] parents, IModule module) {
		Trace.trace(Trace.FINEST, "->- Firing publish state change event: " + module + " ->-");
	
		if (serverListeners == null || serverListeners.isEmpty())
			return;

		int size = serverListeners.size();
		IServerListener[] sl = new IServerListener[size];
		serverListeners.toArray(sl);

		for (int i = 0; i < size; i++) {
			Trace.trace(Trace.FINEST, "  Firing publish state change event to " + sl[i]);
			try {
				sl[i].moduleStateChange(this, parents, module);
			} catch (Exception e) {
				Trace.trace(Trace.SEVERE, "  Error firing publish state change event to " + sl[i], e);
			}
		}

		Trace.trace(Trace.FINEST, "-<- Done firing publish state change event -<-");
	}

	/**
	 * Returns true if the server is in a state that it can
	 * be published to.
	 *
	 * @return boolean
	 */
	public boolean canPublish() {
		// can't publish if the server is starting or stopping
		int state = getServerState();
		if (state == STATE_STARTING ||
			state == STATE_STOPPING)
			return false;
	
		// can't publish if there is no configuration
		if (getServerType() == null || getServerType().hasServerConfiguration() && configuration == null)
			return false;
	
		// return true if the configuration can be published
		if (getServerPublishState() != PUBLISH_STATE_NONE)
			return true;

		// return true if any modules can be published
		class Temp {
			boolean found = false;
		}
		final Temp temp = new Temp();
	
		IModuleVisitor visitor = new IModuleVisitor() {
			public boolean visit(IModule[] parents, IModule module) {
				if (getModulePublishState(module) != PUBLISH_STATE_NONE) {
					temp.found = true;
					return false;
				}
				return true;
			}
		};
		ServerUtil.visit(this, visitor, null);
		
		return temp.found;
	}

	/**
	 * Returns true if the server is in a state that it can
	 * be published to.
	 *
	 * @return boolean
	 */
	public boolean shouldPublish() {
		if (!canPublish())
			return false;
	
		if (getServerPublishState() != PUBLISH_STATE_NONE)
			return true;
	
		if (getUnpublishedModules().length > 0)
			return true;
	
		return false;
	}
	

	/**
	 * Returns a list of the projects that have not been published
	 * since the last modification. (i.e. the projects that are
	 * out of sync with the server.
	 *
	 * @return java.util.List
	 */
	public IModule[] getUnpublishedModules() {
		final List modules = new ArrayList();
		IModuleVisitor visitor = new IModuleVisitor() {
			public boolean visit(IModule[] parents, IModule module) {
				if (getModulePublishState(module) != PUBLISH_STATE_NONE && !modules.contains(module)) {
					PublishControl control = PublishInfo.getPublishInfo().getPublishControl(Server.this, parents, module);
					if (control.isDirty)
						modules.add(module);
				}
				return true;
			}
		};
		ServerUtil.visit(this, visitor, null);
		
		Trace.trace(Trace.FINEST, "Unpublished modules: " + modules);
		
		IModule[] m = new IModule[modules.size()];
		modules.toArray(m);
		return m;
	}

	/**
	 * Publish to the server using the progress monitor. The result of the
	 * publish operation is returned as an IStatus.
	 * 
	 * @param monitor a progress monitor, or <code>null</code> if progress
	 *    reporting and cancellation are not desired
	 * @return status indicating what (if anything) went wrong
	 */
	public IStatus publish(IProgressMonitor monitor) {
		if (getServerType() == null)
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%errorPublishing"), null);

		// check what is out of sync and publish
		if (getServerType().hasServerConfiguration() && configuration == null)
			return new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%errorNoConfiguration"), null);
	
		Trace.trace(Trace.FINEST, "-->-- Publishing to server: " + toString() + " -->--");

		final List parentList = new ArrayList();
		final List moduleList = new ArrayList();
		final List taskParentList = new ArrayList();
		final List taskModuleList = new ArrayList();
		
		IModuleVisitor visitor = new IModuleVisitor() {
			public boolean visit(IModule[] parents, IModule module) {
				taskParentList.add(parents);
				taskModuleList.add(module);
				if (parents != null)
					parentList.add(parents);
				else
					parentList.add(EMPTY_LIST);
				moduleList.add(module);
				return true;
			}
		};

		ServerUtil.visit(this, visitor, monitor);
		
		// get arrays without the server configuration
		List[] taskParents = new List[taskParentList.size()];
		taskParentList.toArray(taskParents);
		IModule[] taskModules = new IModule[taskModuleList.size()];
		taskModuleList.toArray(taskModules);

		// get arrays with the server configuration
		List parents = parentList;
		IModule[] modules = new IModule[moduleList.size()];
		moduleList.toArray(modules);

		int size = 2000 + 3500 * parentList.size();
		
		// find tasks
		List tasks = getTasks(taskParents, taskModules);
		size += tasks.size() * 500;
		
		monitor = ProgressUtil.getMonitorFor(monitor);
		monitor.beginTask(ServerPlugin.getResource("%publishingTask", toString()), size);

		MultiStatus multi = new MultiStatus(ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%publishingStatus"), null);
		
		// perform tasks
		IStatus taskStatus = performTasks(tasks, monitor);
		if (taskStatus != null)
			multi.add(taskStatus);

		if (monitor.isCanceled())
			return new Status(IStatus.INFO, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%publishingCancelled"), null);
		
		// start publishing
		Trace.trace(Trace.FINEST, "Opening connection to the remote server");
		firePublishStarted();
		//boolean connectionOpen = false;
		try {
			getBehaviourDelegate().publishStart(ProgressUtil.getSubMonitorFor(monitor, 1000));
		} catch (CoreException ce) {
			Trace.trace(Trace.INFO, "CoreException publishing to " + toString(), ce);
			firePublishFinished(ce.getStatus());
			return ce.getStatus();
		}
		
		// publish the server
		try {
			if (!monitor.isCanceled() && serverType.hasServerConfiguration()) {
				getBehaviourDelegate().publishServer(ProgressUtil.getSubMonitorFor(monitor, 1000));
			}
		} catch (CoreException ce) {
			Trace.trace(Trace.INFO, "CoreException publishing to " + toString(), ce);
			multi.add(ce.getStatus());
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error publishing configuration to " + toString(), e);
			multi.add(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%errorPublishing"), e));
		}
		
		// remove old modules
	
		// publish modules
		if (!monitor.isCanceled()) {
			publishModules(parents, modules, multi, monitor);
		}
		
		// end the publishing
		Trace.trace(Trace.FINEST, "Closing connection with the remote server");
		try {
			getBehaviourDelegate().publishFinish(ProgressUtil.getSubMonitorFor(monitor, 500));
		} catch (CoreException ce) {
			Trace.trace(Trace.INFO, "CoreException publishing to " + toString(), ce);
			multi.add(ce.getStatus());
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error stopping publish to " + toString(), e);
			multi.add(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%errorPublishing"), e));
		}
		
		if (monitor.isCanceled()) {
			IStatus status = new Status(IStatus.INFO, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%publishingCancelled"), null);
			multi.add(status);
		}

		MultiStatus ps = new MultiStatus(ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%publishingStop"), null);
		ps.add(multi);
		firePublishFinished(multi);
		
		PublishInfo.getPublishInfo().save(this);

		monitor.done();

		Trace.trace(Trace.FINEST, "--<-- Done publishing --<--");
		return multi;
	}

	/**
	 * Publish a single module.
	 */
	protected IStatus publishModule(IModule[] parents, IModule module, IProgressMonitor monitor) {
		Trace.trace(Trace.FINEST, "Publishing module: " + module);
		
		monitor.beginTask(ServerPlugin.getResource("%publishingProject", module.getName()), 1000);
		
		fireModulePublishStarted(parents, module);
		
		Status multi = new Status(IStatus.OK, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%publishingProject", module.getName()), null);
		try {
			getBehaviourDelegate().publishModule(parents, module, monitor);
		} catch (CoreException ce) {
			// ignore
		}
		fireModulePublishFinished(parents, module, multi);
		
		monitor.done();
		
		Trace.trace(Trace.FINEST, "Done publishing: " + module);
		return multi;
	}

	/**
	 * Publishes the given modules. Returns true if the publishing
	 * should continue, or false if publishing has failed or is cancelled.
	 * 
	 * Uses 500 ticks plus 3500 ticks per module
	 */
	protected void publishModules(List parents, IModule[] modules, MultiStatus multi, IProgressMonitor monitor) {
		if (parents == null)
			return;

		int size = parents.size();
		if (size == 0)
			return;
		
		if (monitor.isCanceled())
			return;

		// publish modules
		for (int i = 0; i < size; i++) {
			IStatus status = publishModule((IModule[]) parents.get(i), modules[i], ProgressUtil.getSubMonitorFor(monitor, 3000));
			multi.add(status);
		}
	}

	protected List getTasks(List[] parents, IModule[] modules) {
		List tasks = new ArrayList();
		
		String serverTypeId = getServerType().getId();
		String serverConfigurationTypeId = null;
		if (configuration != null)
			serverConfigurationTypeId = configuration.getServerConfigurationType().getId();
		
		IServerTask[] serverTasks = ServerCore.getServerTasks();
		if (serverTasks != null) {
			int size = serverTasks.length;
			for (int i = 0; i < size; i++) {
				IServerTask task = serverTasks[i];
				if ((task.supportsType(serverTypeId)) || (serverConfigurationTypeId != null && task.supportsType(serverConfigurationTypeId))) {
					IOptionalTask[] tasks2 = task.getTasks(this, configuration, parents, modules);
					if (tasks2 != null) {
						int size2 = tasks2.length;
						for (int j = 0; j < size2; j++) {
							if (tasks2[j].getStatus() == IOptionalTask.TASK_MANDATORY)
								tasks.add(tasks2[j]);
						}
					}
				}
			}
		}

		sortOrderedList(tasks);
		
		return tasks;
	}

	/**
	 * Sort the given list of IOrdered items into indexed order. This method
	 * modifies the original list, but returns the value for convenience.
	 *
	 * @param list java.util.List
	 * @return java.util.List
	 */
	private static List sortOrderedList(List list) {
		if (list == null)
			return null;

		int size = list.size();
		for (int i = 0; i < size - 1; i++) {
			for (int j = i + 1; j < size; j++) {
				IOrdered a = (IOrdered) list.get(i);
				IOrdered b = (IOrdered) list.get(j);
				if (a.getOrder() > b.getOrder()) {
					Object temp = a;
					list.set(i, b);
					list.set(j, temp);
				}
			}
		}
		return list;
	}

	protected IStatus performTasks(List tasks, IProgressMonitor monitor) {
		Trace.trace(Trace.FINEST, "Performing tasks: " + tasks.size());
		
		if (tasks.isEmpty())
			return null;
		
		Status multi = new MultiStatus(ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%taskPerforming"), null);

		Iterator iterator = tasks.iterator();
		while (iterator.hasNext()) {
			IOptionalTask task = (IOptionalTask) iterator.next();
			monitor.subTask(ServerPlugin.getResource("%taskPerforming", task.toString()));
			try {
				task.execute(ProgressUtil.getSubMonitorFor(monitor, 500));
			} catch (CoreException ce) {
				Trace.trace(Trace.SEVERE, "Task failed", ce);
			}
			if (monitor.isCanceled())
				return multi;
		}
		
		// save server and configuration
		/*try {
			ServerUtil.save(server, ProgressUtil.getSubMonitorFor(monitor, 1000));
			ServerUtil.save(configuration, ProgressUtil.getSubMonitorFor(monitor, 1000));
		} catch (CoreException se) {
			Trace.trace(Trace.SEVERE, "Error saving server and/or configuration", se);
			multi.addChild(se.getStatus());
		}*/

		return multi;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.core.runtime.IAdaptable#getAdapter(java.lang.Class)
	 */
	public Object getAdapter(Class adapter) {
		ServerDelegate delegate2 = getDelegate();
		if (adapter.isInstance(delegate2))
			return delegate2;
		ServerBehaviourDelegate delegate3 = getBehaviourDelegate();
		if (adapter.isInstance(delegate3))
			return delegate3;
		return null;
	}

	public String toString() {
		return getName();
	}

	/**
	 * Returns true if the server is in a state that it can
	 * be started, and supports the given mode.
	 *
	 * @param mode
	 * @return boolean
	 */
	public boolean canStart(String mode2) {
		int state = getServerState();
		if (state != STATE_STOPPED && state != STATE_UNKNOWN)
			return false;
		
		if (getServerType() == null || !getServerType().supportsLaunchMode(mode2))
			return false;

		return true;
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

	public void setLaunchDefaults(ILaunchConfigurationWorkingCopy workingCopy, IProgressMonitor monitor) {
		try {
			getBehaviourDelegate().setLaunchDefaults(workingCopy);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate setLaunchDefaults() " + toString(), e);
		}
	}
	
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
					if (getId().equals(serverId))
						return launchConfigs[i];
				} catch (CoreException e) {
					// ignore
				}
			}
		}
		
		if (!create)
			return null;
		
		// create a new launch configuration
		String name = launchManager.generateUniqueLaunchConfigurationNameFrom(getName()); 
		ILaunchConfigurationWorkingCopy wc = launchConfigType.newInstance(null, name);
		wc.setAttribute(SERVER_ID, getId());
		setLaunchDefaults(wc, monitor);
		return wc.doSave();
	}

	/**
	 * Start the server in the given mode.
	 *
	 * @param launchMode String
	 * @param monitor org.eclipse.core.runtime.IProgressMonitor
	 * @return org.eclispe.core.runtime.IStatus
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
	 * Clean up any launch configurations with the given server ref.
	 * 
	 * @param serverRef java.lang.String
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
	 * Returns true if the server is in a state that it can
	 * be restarted.
	 *
	 * @return boolean
	 */
	public boolean canRestart(String mode2) {
		/*ServerDelegate delegate2 = getDelegate();
		if (!(delegate2 instanceof IStartableServer))
			return false;*/
		if (!getServerType().supportsLaunchMode(mode2))
			return false;

		int state = getServerState();
		return (state == STATE_STARTED);
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
	 * Restart the server with the given debug mode.
	 * A server may only be restarted when it is currently running.
	 * This method is asynchronous.
	 */
	public void restart(final String mode2) {
		if (getServerState() == STATE_STOPPED)
			return;
	
		Trace.trace(Trace.FINEST, "Restarting server: " + getName());
	
		try {
			try {
				getBehaviourDelegate().restart(mode2);
				return;
			} catch (CoreException ce) {
				Trace.trace(Trace.SEVERE, "Error calling delegate restart() " + toString());
			}
		
			// add listener to start it as soon as it is stopped
			addServerListener(new ServerAdapter() {
				public void serverStateChange(IServer server) {
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
	public boolean canStop() {
		if (getServerState() == STATE_STOPPED)
			return false;

		return true;
	}

	/**
	 * Stop the server if it is running.
	 */
	public void stop(boolean force) {
		if (getServerState() == STATE_STOPPED)
			return;

		Trace.trace(Trace.FINEST, "Stopping server: " + toString());

		try {
			getBehaviourDelegate().stop(force);
		} catch (Throwable t) {
			Trace.trace(Trace.SEVERE, "Error calling delegate stop() " + toString(), t);
		}
	}
	
	/**
	 * Start the server in the given start mode and waits until the server
	 * has finished started.
	 *
	 * @param mode java.lang.String
	 * @param monitor org.eclipse.core.runtime.IProgressMonitor
	 * @exception org.eclipse.core.runtime.CoreException - thrown if an error occurs while trying to start the server
	 */
	public void synchronousStart(String mode2, IProgressMonitor monitor) throws CoreException {
		Trace.trace(Trace.FINEST, "synchronousStart 1");
		final Object mutex = new Object();
	
		// add listener to the server
		IServerListener listener = new ServerAdapter() {
			public void serverStateChange(IServer server) {
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
		try {
			start(mode2, monitor);
		} catch (CoreException e) {
			removeServerListener(listener);
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
		
		if (timer.timeout)
			throw new CoreException(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%errorStartFailed", getName()), null));
		timer.alreadyDone = true;
		
		if (getServerState() == IServer.STATE_STOPPED)
			throw new CoreException(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%errorStartFailed", getName()), null));
	
		Trace.trace(Trace.FINEST, "synchronousStart 4");
	}
	
	/*
	 * @see IServer#synchronousRestart(String, IProgressMonitor)
	 */
	public void synchronousRestart(String mode2, IProgressMonitor monitor) throws CoreException {
		// TODO
	}

	/*
	 * @see IServer#synchronousStop()
	 */
	public void synchronousStop() {
		if (getServerState() == IServer.STATE_STOPPED)
			return;
		
		final Object mutex = new Object();
	
		// add listener to the server
		IServerListener listener = new ServerAdapter() {
			public void serverStateChange(IServer server) {
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
		stop(false);
	
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
	public void synchronousRestartModule(final IModule module, IProgressMonitor monitor) throws CoreException {
		Trace.trace(Trace.FINEST, "synchronousModuleRestart 1");

		final Object mutex = new Object();
	
		// add listener to the module
		IServerListener listener = new ServerAdapter() {
			public void moduleStateChange(IServer server) {
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
			getBehaviourDelegate().restartModule(module, monitor);
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
			throw new CoreException(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%errorModuleRestartFailed", getName()), null));
		timer.alreadyDone = true;
		
		if (getModuleState(module) == IServer.STATE_STOPPED)
			throw new CoreException(new Status(IStatus.ERROR, ServerPlugin.PLUGIN_ID, 0, ServerPlugin.getResource("%errorModuleRestartFailed", getName()), null));
	
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
		serverType = ServerCore.findServerType(serverTypeId);
		if (serverType != null && !serverType.equals(oldServerType))
			serverState = ((ServerType)serverType).getInitialState();
		
		String runtimeId = getAttribute(RUNTIME_ID, (String)null);
		runtime = ServerCore.findRuntime(runtimeId);
		
		String configurationId = getAttribute(CONFIGURATION_ID, (String)null);
		configuration = ServerCore.findServerConfiguration(configurationId);
	}
	
	protected void setInternal(ServerWorkingCopy wc) {
		map = wc.map;
		configuration = wc.configuration;
		runtime = wc.runtime;
		serverSyncState = wc.serverSyncState;
		//restartNeeded = wc.restartNeeded;
		serverType = wc.serverType;

		// can never modify the following properties via the working copy
		//serverState = wc.serverState;
		delegate = wc.delegate;
	}
	
	protected void saveState(IMemento memento) {
		if (serverType != null)
			memento.putString("server-type", serverType.getId());

		if (configuration != null)
			memento.putString(CONFIGURATION_ID, configuration.getId());
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
			return getDelegate().canModifyModules(add, remove);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate canModifyModules() " + toString(), e);
			return null;
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.wst.server.core.IServer#getModules()
	 */
	public IModule[] getModules(IProgressMonitor monitor) {
		try {
			return getDelegate().getModules();
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate getModules() " + toString(), e);
			return new IModule[0];
		}
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.wst.server.core.IServer#getModuleState()
	 */
	public int getModuleState(IModule module) {
		try {
			Integer in = (Integer) moduleState.get(module.getId());
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
	public int getModulePublishState(IModule module) {
		try {
			Integer in = (Integer) modulePublishState.get(module.getId());
			if (in != null)
				return in.intValue();
		} catch (Exception e) {
			// ignore
		}
		return PUBLISH_STATE_UNKNOWN;
	}

	/*
	 * @see IServer#getChildModule(IModule)
	 */
	public IModule[] getChildModules(IModule module, IProgressMonitor monitor) {
		try {
			return getDelegate().getChildModules(module);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate getChildModules() " + toString(), e);
			return null;
		}
	}

	/*
	 * @see IServer#getParentModules(IModule)
	 */
	public IModule[] getParentModules(IModule module, IProgressMonitor monitor) throws CoreException {
		try {
			return getDelegate().getParentModules(module);
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
	 * @return <code>true</code> if the given module can be
	 * restarted, and <code>false</code> otherwise 
	 */
	public boolean canRestartModule(IModule module) {
		try {
			return getBehaviourDelegate().canRestartModule(module);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate canRestartRuntime() " + toString(), e);
			return false;
		}
	}

	/**
	 * Check if the given module is in sync on the server. It should
	 * return true if the module should be restarted (is out of
	 * sync) or false if the module does not need to be restarted.
	 *
	 * @param module org.eclipse.wst.server.core.model.IModule
	 * @return boolean
	 */
	public boolean getModuleRestartState(IModule module) {
		try {
			Boolean b = (Boolean) moduleRestartState.get(module.getId());
			if (b != null)
				return b.booleanValue();
		} catch (Exception e) {
			// ignore
		}
		return false;
	}

	/*
	 * @see IServer#restartModule(IModule, IProgressMonitor)
	 */
	public void restartModule(IModule module, IProgressMonitor monitor) throws CoreException {
		try {
			getBehaviourDelegate().restartModule(module, monitor);
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate restartModule() " + toString(), e);
		}
	}
	
	/**
	 * Returns an array of IServerPorts that this server has.
	 *
	 * @return 
	 */
	public IServerPort[] getServerPorts() {
		try {
			return getDelegate().getServerPorts();
		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error calling delegate getServerPorts() " + toString(), e);
			return null;
		}
	}
}