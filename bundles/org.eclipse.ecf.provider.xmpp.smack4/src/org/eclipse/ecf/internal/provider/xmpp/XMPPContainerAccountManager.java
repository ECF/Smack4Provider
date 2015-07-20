/*******************************************************************************
 * Copyright (c) 2015 Composent, Inc. and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors: Composent, Inc. - initial API and implementation
 ******************************************************************************/
package org.eclipse.ecf.internal.provider.xmpp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.ecf.core.util.ECFException;
import org.eclipse.ecf.presence.IAccountManager;
import org.jivesoftware.smackx.iqregister.AccountManager;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;

public class XMPPContainerAccountManager implements IAccountManager {

	private static final String NOT_CONNECTED = "not connected";

	private AccountManager accountManager = null;

	protected void traceAndThrow(String msg, Throwable t) throws ECFException {
		throw new ECFException(msg, t);
	}

	protected AccountManager getAccountManagerOrThrowIfNull() throws ECFException {
		if (accountManager == null)
			throw new ECFException(NOT_CONNECTED);
		return accountManager;
	}

	public XMPPContainerAccountManager() {
	}

	public void dispose() {
		accountManager = null;
	}

	public void setConnection(XMPPConnection connection) {
		this.accountManager = (connection == null) ? null : AccountManager.getInstance(connection);
	}

	public boolean changePassword(String newpassword) throws ECFException {
		try {
			getAccountManagerOrThrowIfNull().changePassword(newpassword);
		} catch (NotConnectedException | NoResponseException | XMPPErrorException e) {
			traceAndThrow("Could not change password", e);
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	public boolean createAccount(String username, String password, @SuppressWarnings("rawtypes") Map attributes)
			throws ECFException {
		try {
			getAccountManagerOrThrowIfNull().createAccount(username, password, (Map<String, String>) attributes);
		} catch (XMPPException | NoResponseException | NotConnectedException e) {
			traceAndThrow("server exception creating account for " + username, e);
		}
		return true;
	}

	public boolean deleteAccount() throws ECFException {
		try {
			getAccountManagerOrThrowIfNull().deleteAccount();
		} catch (XMPPException | NoResponseException | NotConnectedException e) {
			traceAndThrow("server exception deleting account", e);
		}
		return true;
	}

	public String getAccountCreationInstructions() {
		if (accountManager == null)
			return "";
		try {
			return accountManager.getAccountInstructions();
		} catch (NotConnectedException | NoResponseException | XMPPErrorException e) {
			XmppPlugin.log("Could not get account instructions", e);
		}
		return "";
	}

	public String[] getAccountAttributeNames() {
		if (accountManager == null)
			return new String[0];
		Iterator<String> i;
		List<String> l = new ArrayList<String>();
		try {
			i = accountManager.getAccountAttributes().iterator();
			for (; i.hasNext();)
				l.add(i.next());
		} catch (NoResponseException | XMPPErrorException | NotConnectedException e) {
			XmppPlugin.log("Coul dnot get account attributes", e);
		}
		return (String[]) l.toArray(new String[l.size()]);
	}

	public Object getAccountAttribute(String name) {
		if (accountManager == null)
			return null;
		try {
			return accountManager.getAccountAttribute(name);
		} catch (NoResponseException | XMPPErrorException | NotConnectedException e) {
			XmppPlugin.log("Could not get account attributes for name=" + name, e);
		}
		return false;
	}

	public boolean isAccountCreationSupported() {
		if (accountManager == null)
			return false;
		try {
			return accountManager.supportsAccountCreation();
		} catch (NotConnectedException | NoResponseException | XMPPErrorException e) {
			XmppPlugin.log("Could determine if account manager supports account creation", e);
		}
		return false;
	}

}
