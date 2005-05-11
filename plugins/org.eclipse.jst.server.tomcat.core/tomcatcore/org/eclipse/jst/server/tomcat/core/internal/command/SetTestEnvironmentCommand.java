/*******************************************************************************
 * Copyright (c) 2003, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - Initial API and implementation
 *******************************************************************************/
package org.eclipse.jst.server.tomcat.core.internal.command;

import org.eclipse.jst.server.tomcat.core.internal.ITomcatServerWorkingCopy;
import org.eclipse.jst.server.tomcat.core.internal.Messages;
/**
 * Command to change the server debug mode.
 */
public class SetTestEnvironmentCommand extends ServerCommand {
	protected boolean te;
	protected boolean oldTe;

	/**
	 * SetTestEnvironmentCommand constructor comment.
	 * 
	 * @param server a Tomcat server
	 * @param te <code>true</code> for a test environment.
	 */
	public SetTestEnvironmentCommand(ITomcatServerWorkingCopy server, boolean te) {
		super(server, Messages.serverEditorActionSetTestEnvironment);
		this.te = te;
	}

	/**
	 * Execute the command.
	 */
	public void execute() {
		oldTe = server.isTestEnvironment();
		server.setTestEnvironment(te);
	}

	/**
	 * Undo the command.
	 */
	public void undo() {
		server.setTestEnvironment(oldTe);
	}
}