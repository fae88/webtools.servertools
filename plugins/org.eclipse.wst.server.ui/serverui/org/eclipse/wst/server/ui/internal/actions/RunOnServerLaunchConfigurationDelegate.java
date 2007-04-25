/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - Initial API and implementation
 *******************************************************************************/
package org.eclipse.wst.server.ui.internal.actions;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.LaunchConfigurationDelegate;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.launchConfigurations.LaunchConfigurationManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.ServerCore;
import org.eclipse.wst.server.core.internal.ChainedJob;
import org.eclipse.wst.server.core.internal.IClient;
import org.eclipse.wst.server.core.internal.ILaunchableAdapter;
import org.eclipse.wst.server.core.internal.PublishServerJob;
import org.eclipse.wst.server.core.internal.RestartServerJob;
import org.eclipse.wst.server.core.internal.ServerPlugin;
import org.eclipse.wst.server.core.internal.ServerType;
import org.eclipse.wst.server.core.internal.StartServerJob;
import org.eclipse.wst.server.core.model.ModuleArtifactDelegate;
import org.eclipse.wst.server.ui.internal.EclipseUtil;
import org.eclipse.wst.server.ui.internal.LaunchClientJob;
import org.eclipse.wst.server.ui.internal.Messages;
import org.eclipse.wst.server.ui.internal.ServerUIPlugin;
import org.eclipse.wst.server.ui.internal.Trace;

public class RunOnServerLaunchConfigurationDelegate extends LaunchConfigurationDelegate {
	public static final String ATTR_SERVER_ID = "server-id";
	public static final String ATTR_MODULE_ARTIFACT = "module-artifact";
	public static final String ATTR_MODULE_ARTIFACT_CLASS = "module-artifact-class";

	public static final String ATTR_LAUNCHABLE_ADAPTER_ID = "launchable-adapter-id";
	public static final String ATTR_CLIENT_ID = "client-id";

	public void launch(ILaunchConfiguration configuration, String launchMode, final ILaunch launch2,
			IProgressMonitor monitor) throws CoreException {
		
		String serverId = configuration.getAttribute(ATTR_SERVER_ID, (String)null);
		String moduleArt = configuration.getAttribute(ATTR_MODULE_ARTIFACT, (String)null);
		String moduleArtifactClass = configuration.getAttribute(ATTR_MODULE_ARTIFACT_CLASS, (String)null);
		String laId = configuration.getAttribute(ATTR_LAUNCHABLE_ADAPTER_ID, (String)null);
		String clientId = configuration.getAttribute(ATTR_CLIENT_ID, (String)null);
		
		IServer server = ServerCore.findServer(serverId);
		IModule module = null;
		ModuleArtifactDelegate moduleArtifact = null;
		ILaunchableAdapter launchableAdapter = ServerPlugin.findLaunchableAdapter(laId);
		IClient client = ServerPlugin.findClient(clientId);
		
		try {
			Class c = Class.forName(moduleArtifactClass);
			moduleArtifact = (ModuleArtifactDelegate) c.newInstance();
			moduleArtifact.deserialize(moduleArt);
			module = moduleArtifact.getModule();
		} catch (Throwable t) {
			Trace.trace(Trace.WARNING, "Could not load module artifact delegate class");
		}
		
		if (moduleArtifact == null)
			throw new CoreException(new Status(IStatus.ERROR, ServerUIPlugin.PLUGIN_ID, Messages.errorLaunchConfig));
		
		if (module == null)
			throw new CoreException(new Status(IStatus.ERROR, ServerUIPlugin.PLUGIN_ID, Messages.errorLaunchConfig));
		
		if (server == null)
			throw new CoreException(new Status(IStatus.ERROR, ServerUIPlugin.PLUGIN_ID, Messages.errorInvalidServer));
		
		if (launchableAdapter == null)
			throw new CoreException(new Status(IStatus.ERROR, ServerUIPlugin.PLUGIN_ID, Messages.errorLaunchConfig));
		
		final Shell[] shell2 = new Shell[1];
		Display.getDefault().syncExec(new Runnable() {
			public void run() {
				shell2[0] = EclipseUtil.getShell();
			}
		});
		Shell shell = shell2[0];
		
		if (client == null) {
			// if there is no client, use a dummy
			client = new IClient() {
				public String getDescription() {
					return Messages.clientDefaultDescription;
				}

				public String getId() {
					return "org.eclipse.wst.server.ui.client.default";
				}

				public String getName() {
					return Messages.clientDefaultName;
				}

				public IStatus launch(IServer server3, Object launchable2, String launchMode3, ILaunch launch) {
					return Status.OK_STATUS;
				}

				public boolean supports(IServer server3, Object launchable2, String launchMode3) {
					return true;
				}
			};
		}
		
		Trace.trace(Trace.FINEST, "Ready to launch");
		
		// start server if it's not already started
		// and cue the client to start
		IModule[] modules = new IModule[] { module }; // TODO: get parent heirarchy correct
		int state = server.getServerState();
		if (state == IServer.STATE_STARTING) {
			LaunchClientJob clientJob = new LaunchClientJob(server, modules, launchMode, moduleArtifact, launchableAdapter, client);
			clientJob.schedule();
		} else if (state == IServer.STATE_STARTED) {
			boolean restart = false;
			String mode = server.getMode();
			IBreakpointManager breakpointManager = DebugPlugin.getDefault().getBreakpointManager();
			boolean disabledBreakpoints = false;
			
			if (server.getServerRestartState()) {
				int result = RunOnServerActionDelegate.openRestartDialog(shell);
				if (result == 0) {
					launchMode = mode;
					restart = true;
				} else if (result == 9) // cancel
					return;
			}
			if (!restart) {
				if (!ILaunchManager.RUN_MODE.equals(mode) && ILaunchManager.RUN_MODE.equals(launchMode)) {
					boolean breakpointsOption = false;
					if (breakpointManager.isEnabled() && ILaunchManager.DEBUG_MODE.equals(mode))
						breakpointsOption = true;
					int result = RunOnServerActionDelegate.openOptionsDialog(shell, Messages.wizRunOnServerTitle, Messages.dialogModeWarningRun, breakpointsOption);
					if (result == 0)
						restart = true;
					else if (result == 1) {
						breakpointManager.setEnabled(false);
						disabledBreakpoints = true;
						launchMode = mode;
					} else if (result == 2)
						launchMode = mode;
					else // result == 9 // cancel
						return;
				} else if (!ILaunchManager.DEBUG_MODE.equals(mode) && ILaunchManager.DEBUG_MODE.equals(launchMode)) {
					int result = RunOnServerActionDelegate.openOptionsDialog(shell, Messages.wizDebugOnServerTitle, Messages.dialogModeWarningDebug, false);
					if (result == 0)
						restart = true;
					else if (result == 1)
						launchMode = mode;
					else // result == 9 // cancel
						return;
				} else if (!ILaunchManager.PROFILE_MODE.equals(mode) && ILaunchManager.PROFILE_MODE.equals(launchMode)) {
					boolean breakpointsOption = false;
					if (breakpointManager.isEnabled() && ILaunchManager.DEBUG_MODE.equals(mode))
						breakpointsOption = true;
					int result = RunOnServerActionDelegate.openOptionsDialog(shell, Messages.wizProfileOnServerTitle, Messages.dialogModeWarningProfile, breakpointsOption);
					if (result == 0)
						restart = true;
					else if (result == 1) {
						breakpointManager.setEnabled(false);
						disabledBreakpoints = true;
						launchMode = mode;
					} else if (result == 2)
						launchMode = mode;
					else // result == 9 // cancel
						return;
				}
				
				if (ILaunchManager.DEBUG_MODE.equals(launchMode)) {
					if (!breakpointManager.isEnabled() && !disabledBreakpoints) {
						int result = RunOnServerActionDelegate.openBreakpointDialog(shell);
						if (result == 0)
							breakpointManager.setEnabled(true);
						else if (result == 1) {
							// ignore
						} else // result == 2
							return;
					}
				}
			}
			
			PublishServerJob publishJob = new PublishServerJob(server, IServer.PUBLISH_INCREMENTAL, false);
			LaunchClientJob clientJob = new LaunchClientJob(server, modules, launchMode, moduleArtifact, launchableAdapter, client);
			publishJob.setNextJob(clientJob);
			
			if (restart) {
				RestartServerJob restartJob = new RestartServerJob(server, launchMode);
				restartJob.setNextJob(publishJob);
				restartJob.schedule();
			} else
				publishJob.schedule();
		} else if (state != IServer.STATE_STOPPING) {
			PublishServerJob publishJob = new PublishServerJob(server);
			StartServerJob startServerJob = new StartServerJob(server, launchMode);
			LaunchClientJob clientJob = new LaunchClientJob(server, modules, launchMode, moduleArtifact, launchableAdapter, client);
			
			ChainedJob myJob = new ChainedJob("test", server) {
				protected IStatus run(IProgressMonitor monitor2) {
					try {
						LaunchConfigurationManager lcm = DebugUIPlugin.getDefault().getLaunchConfigurationManager();
						lcm.setRecentLaunch(launch2);
					} catch (Throwable t) {
						Trace.trace(Trace.WARNING, "Could not tweak debug launch history");
					}
					return Status.OK_STATUS;
				}
			};
			
			if (((ServerType)server.getServerType()).startBeforePublish()) {
				startServerJob.setNextJob(publishJob);
				publishJob.setNextJob(clientJob);
				clientJob.setNextJob(myJob);
				startServerJob.schedule();
			} else {
				publishJob.setNextJob(startServerJob);
				startServerJob.setNextJob(clientJob);
				clientJob.setNextJob(myJob);
				publishJob.schedule();
			}
		}
	}
}