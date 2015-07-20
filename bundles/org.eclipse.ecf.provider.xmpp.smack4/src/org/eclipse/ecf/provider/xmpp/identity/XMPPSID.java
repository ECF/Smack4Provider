/****************************************************************************
 * Copyright (c) 2015 Composent, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Composent, Inc. - initial API and implementation
 *****************************************************************************/
package org.eclipse.ecf.provider.xmpp.identity;

import java.net.URISyntaxException;
import org.eclipse.ecf.core.identity.Namespace;

public class XMPPSID extends XMPPID {

	private static final long serialVersionUID = -7665808387581704917L;

	public XMPPSID(Namespace namespace, String unamehost) throws URISyntaxException {
		super(namespace, unamehost);
	}

	public String toString() {
		StringBuffer sb = new StringBuffer("XMPPSID["); //$NON-NLS-1$
		sb.append(toExternalForm()).append("]"); //$NON-NLS-1$
		return sb.toString();
	}

}
