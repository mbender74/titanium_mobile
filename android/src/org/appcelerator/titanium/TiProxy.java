/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2010 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package org.appcelerator.titanium;

import java.util.concurrent.CountDownLatch;

import org.appcelerator.titanium.bridge.OnEventListenerChange;
import org.appcelerator.titanium.kroll.KrollCallback;
import org.appcelerator.titanium.util.Log;
import org.appcelerator.titanium.util.TiConfig;
import org.appcelerator.titanium.util.TiConvert;

import android.os.Handler;
import android.os.Message;

public class TiProxy implements Handler.Callback, TiDynamicMethod, OnEventListenerChange

{
	private static final String LCAT = "TiProxy";
	private static final boolean DBG = TiConfig.LOGD;

	protected static final int MSG_MODEL_PROPERTY_CHANGE = 100;
	protected static final int MSG_LISTENER_ADDED = 101;
	protected static final int MSG_LISTENER_REMOVED = 102;

	protected static final int MSG_LAST_ID = 999;

	private TiContext tiContext;
	private Handler uiHandler;
	private CountDownLatch waitForHandler;

	public TiDict getConstants() {
		return null;
	}

	// TODO consider using a single object or a pool of them.
	private static class PropertyChangeHolder
	{
		private TiProxyListener modelListener;
		private String key;
		private Object current;
		private Object value;
		private TiProxy proxy;

		PropertyChangeHolder(TiProxyListener modelListener, String key, Object current, Object value, TiProxy proxy)
		{
			this.modelListener = modelListener;
			this.key = key;
			this.current = current;
			this.value = value;
			this.proxy = proxy;
		}

		public void fireEvent() {
			try {
				modelListener.propertyChanged(key, current, value, proxy);
			} finally {
				modelListener = null;
				proxy = null;
			}
		}
	}

	protected TiDict dynprops; // Dynamic properties
	protected String proxyId; //TODO implement
	protected TiProxyListener modelListener;

	public TiProxy(TiContext tiContext)
	{
		if (DBG) {
			Log.d(LCAT, "New: " + getClass().getSimpleName());
		}
		this.tiContext = tiContext;
		final TiProxy me = this;
		waitForHandler = new CountDownLatch(1);

		if (tiContext.isUIThread()) {
			uiHandler = new Handler(me);
			waitForHandler.countDown();
		} else {
			tiContext.getActivity().runOnUiThread(new Runnable()
			{
				public void run() {
					uiHandler = new Handler(me);
					waitForHandler.countDown();
				}
			});
		}
	}

	protected Handler getUIHandler() {
		try {
			waitForHandler.await();
		} catch (InterruptedException e) {
			// ignore
		}
		return uiHandler;
	}

	public TiContext getTiContext() {
		return tiContext;
	}

	public boolean handleMessage(Message msg) {
		switch (msg.what) {
			case MSG_MODEL_PROPERTY_CHANGE : {
				PropertyChangeHolder pch = (PropertyChangeHolder) msg.obj;
				pch.fireEvent();
				return true;
			}
			case MSG_LISTENER_ADDED : {
				if (modelListener != null) {
					modelListener.listenerAdded(msg.getData().getString("eventName"), msg.arg1, (TiProxy) msg.obj);
				}
				return true;
			}
			case MSG_LISTENER_REMOVED : {
				if (modelListener != null) {
					modelListener.listenerRemoved(msg.getData().getString("eventName"), msg.arg1, (TiProxy) msg.obj);
				}
				return true;
			}
		}
		return false;
	}

	public Object getDynamicValue(String key)
	{
		Object result = null;

		if (dynprops != null) {
			result = dynprops.get(key);
		}
		return result;
	}

	public void setDynamicValue(String key, Object value) {
		internalSetDynamicValue(key, value, true);
	}

	public void internalSetDynamicValue(String key, Object value, boolean fireChange)
	{
		if (dynprops == null) {
			dynprops = new TiDict();
		}

		// Get the current value if it exists.
		Object current = dynprops.get(key);
		value = TiConvert.putInTiDict(dynprops, key, value);

		if (fireChange) {
			if ((current == null && value != null) || (value == null && current != null) || (!current.equals(value))) {
				if (modelListener != null) {
					if (tiContext.isUIThread()) {
						modelListener.propertyChanged(key, current, value, this);
					} else {
						PropertyChangeHolder pch = new PropertyChangeHolder(modelListener, key, current, value, this);
						getUIHandler().obtainMessage(MSG_MODEL_PROPERTY_CHANGE, pch).sendToTarget();
					}
				}
			}
		}
	}

	public TiDict getDynamicProperties() {
		return dynprops;
	}

	public int addEventListener(String eventName, Object listener) {
		int listenerId = -1;

		Log.i(LCAT, "Adding listener: " + listener.getClass().getName());
		TiContext ctx = getTiContext();
		if (ctx != null) {
			listenerId = ctx.addEventListener(eventName, this, listener);
		}

		return listenerId;
	}

	public void removeEventListener(String eventName, int listenerId)
	{
		TiContext ctx = getTiContext();
		if (ctx != null) {
			ctx.removeEventListener(eventName, listenerId);
		}
	}

	protected void setProperties(TiDict options)
	{
		if (options != null) {
			for (String key : options.keySet()) {
				setDynamicValue(key, options.get(key));
			}
		}
	}

	public void eventListenerAdded(String eventName, int count, TiProxy proxy)
	{
		if (modelListener != null) {
			Message m = getUIHandler().obtainMessage(MSG_LISTENER_ADDED, count, -1, proxy);
			m.getData().putString("eventName", eventName);
			m.sendToTarget();
		}
	}

	public void eventListenerRemoved(String eventName, int count, TiProxy proxy) {
		if (modelListener != null) {
			Message m = getUIHandler().obtainMessage(MSG_LISTENER_REMOVED, count, -1, proxy);
			m.getData().putString("eventName", eventName);
			m.sendToTarget();
		}
	}

	public void fireEvent(String eventName, TiDict data) {
		TiContext ctx = getTiContext();
		if (ctx != null) {
			ctx.dispatchEvent(this, eventName, data);
		}
	}

	public void fireEvent(String eventName, Object listener, TiDict data)
	{
		if (listener != null) {
			KrollCallback callback = (KrollCallback) listener;
			if (data == null) {
				data = new TiDict();
			}
			callback.call(data);
		}
	}

	public Object resultForUndefinedMethod(String name, Object[] args) {
		throw new UnsupportedOperationException("Method: " + name + " not supported by " + getClass().getSimpleName());
	}

	protected TiDict createErrorResponse(int code, String message)
	{
		TiDict error = new TiDict();

		error.put("code", code);
		error.put("message", message);

		return error;
	}
}
