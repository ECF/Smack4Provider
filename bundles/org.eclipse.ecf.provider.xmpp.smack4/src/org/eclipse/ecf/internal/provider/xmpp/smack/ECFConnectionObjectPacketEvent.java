/*******************************************************************************
 * Copyright (c) 2015 Composent, Inc. and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: Composent, Inc. - initial API and implementation
 ******************************************************************************/
package org.eclipse.ecf.internal.provider.xmpp.smack;

import org.eclipse.ecf.provider.comm.AsynchEvent;
import org.eclipse.ecf.provider.comm.IAsynchConnection;
import org.jivesoftware.smack.packet.Stanza;

public class ECFConnectionObjectPacketEvent extends AsynchEvent {

	Object value;

	public ECFConnectionObjectPacketEvent(IAsynchConnection source, Stanza arg0, Object obj) {
		super(source, arg0);
		this.value = obj;
	}

	public Object getObjectValue() {
		return value;
	}

	public String toString() {
		StringBuffer sb = new StringBuffer("ECFConnectionPacketEvent[");
		sb.append(getData()).append(";");
		sb.append(getConnection()).append(";");
		sb.append(getObjectValue()).append("]");
		return sb.toString();
	}
}
