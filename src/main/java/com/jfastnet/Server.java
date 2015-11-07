/*******************************************************************************
 * Copyright 2015 Klaus Pfeiffer <klaus@allpiper.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.jfastnet;

import com.jfastnet.messages.*;
import com.jfastnet.util.NullsafeHashMap;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** @author Klaus Pfeiffer - klaus@allpiper.com */
@Slf4j
public class Server extends PeerController {

	/** The server holds track of its clients. */
	protected Map<Integer, InetSocketAddress> clients = new ConcurrentHashMap<>();

	/** Timestamp of time when a message was last received from client id.
	 * Key: client id; Value: timestamp */
	protected Map<Integer, Long> lastReceivedMap = new ConcurrentHashMap<>();

	/** Track count of incoming messages. */
	protected Map<Class, Counter> incomingMessages = new NullsafeCounterHashMap();

	/** Track count of outgoing messages. */
	protected Map<Class, Counter> outgoingMessages = new NullsafeCounterHashMap();

	private long lastKeepAliveCheck;

	public Server(Config config) {
		super(config.setHost(true));
	}

	@Override
	public boolean start() {
		boolean started = super.start();
		if (started) {
			config.connected = true;
		}
		return started;
	}

	@Override
	public void process() {
		super.process();

		long currentTime = config.timeProvider.get();
		if (clients.size() > 0 && lastKeepAliveCheck + config.keepAliveInterval < currentTime) {

			// Potentially "Keep Alive" will be sent, when first client joins.
			// This can lead to clients that join a few milliseconds later that
			// request this message as a missing packet, because the id number
			// is already raised. Only happens with the ReliableModeIdProvider class.

			lastKeepAliveCheck = currentTime;
			send(new SequenceKeepAlive());
		}

		for (Map.Entry<Integer, Long> entry : lastReceivedMap.entrySet()) {
			Long lastReceivedTime = entry.getValue();
			if (lastReceivedTime + config.timeoutThreshold < currentTime) {
				// timed out
				unregister(entry.getKey());
			}
		}
	}

	@Override
	public void receive(Message message) {
		if (!clients.containsValue(message.getSocketAddressSender())) {
			if (!(message instanceof ConnectRequest)) {
				log.warn("No client found under {}", message.getSocketAddressSender());
				return;
			}
		}

		incomingMessages.get(message.getClass()).value++;
		long lastReceived = lastReceivedMap.getOrDefault(message.getSenderId(), 0L);
		if (message.getSenderId() > 0) {
			lastReceivedMap.put(message.getSenderId(), config.timeProvider.get());
		}

		if (message instanceof LeaveRequest) {
			unregister(message.getSenderId());
		} else if (message instanceof ConnectRequest && config.timeProvider.get() - lastReceived > config.timeSinceLastConnectRequest) {
			int clientId = ((ConnectRequest) message).getClientId();

			// Unregister if client was already added, maybe it's a re-connect
			if (clients.containsKey(clientId)) {
				log.info("Client {} is already in list - could be a re-join.", clientId);
				unregister(clientId);
			}
			if (config.expectedClientIds.isEmpty() || config.expectedClientIds.contains(clientId)) {
				config.requiredClients.put(clientId, false);
			}
			clients.put(clientId, message.getSocketAddressSender());
			log.info("Added {} with address {} to clients.", clientId, message.getSocketAddressSender());
			config.processors.stream().filter(o -> o instanceof IServerHooks).forEach(o1 -> ((IServerHooks) o1).onRegister(clientId));
			config.serverHooks.onRegister(clientId);
		}

		if (message instanceof IInstantServerProcessable) {
			IInstantServerProcessable instantServerProcessable = (IInstantServerProcessable) message;
			instantServerProcessable.process();
		} else {
			super.receive(message);
		}

		if (message.broadcast()) {
			// clear id so a new id gets assigned to the message
			message.clearId();
			message.setReceiverId(0);
			if (message.sendBroadcastBackToSender()) {
				internalSend(message, 0);
			} else {
				// don't send broadcast message back to sender
				internalSend(message, message.getSenderId());
			}
		}
	}

	@Override
	public boolean send(Message message) {
		if (config.idProvider.resolveEveryClientMessage()) {
			return internalSend(message, 0);
		} else {
			return internalSendSameIds(message, 0);
		}
	}

	private boolean internalSend(Message message, int exceptId) {
		int receiverId = message.getReceiverId();
		if (receiverId > 0) {
			return send(receiverId, message);
		}

		if (!resolveMessage(message)) {
			return false;
		}

		if (!message.isResendMessage()) {
			// only track messages sent to all players
			outgoingMessages.get(message.getClass()).value++;
		}
		if (!config.idProvider.resolveEveryClientMessage()) {
			if (!createPayload(message)) {
				return false;
			}
		}

		boolean beforeSendState = true;
		boolean afterSendState = true;
		for (Map.Entry<Integer, InetSocketAddress> entry : clients.entrySet()) {
			Integer clientId = entry.getKey();
			if (exceptId > 0 && exceptId == clientId) {
				continue;
			}
			message.setReceiverId(clientId);
			if (config.idProvider.resolveEveryClientMessage()) {
				message.resolveId();
				if (!createPayload(message)) {
					beforeSendState = false;
				}
			}
			message.socketAddressRecipient = entry.getValue();

			beforeSendState &= super.beforeSend(message);
			config.udpPeer.send(message);
			afterSendState &= super.afterSend(message);
		}
		log.trace("Sent message: {}", message);

		if (!beforeSendState || !afterSendState) {
			// Something went wrong
			return false;
		}

		// Keep alive only has to be sent, when no other messages are sent
		lastKeepAliveCheck = config.timeProvider.get();
		return true;
	}

	private boolean internalSendSameIds(Message message, int exceptId) {
		int receiverId = message.getReceiverId();
		if (receiverId > 0) {
			return send(receiverId, message);
		}

		if (!resolveMessage(message)) {
			return false;
		}

		if (!beforeSend(message)) {
			return false;
		}

		if (!message.isResendMessage()) {
			// only track messages sent to all players
			outgoingMessages.get(message.getClass()).value++;
		}

		if (!createPayload(message)) {
			return false;
		}

		for (Map.Entry<Integer, InetSocketAddress> entry : clients.entrySet()) {
			Integer clientId = entry.getKey();
			if (exceptId > 0 && exceptId == clientId) {
				continue;
			}
			message.setReceiverId(clientId);
			message.socketAddressRecipient = entry.getValue();
			config.udpPeer.send(message);
		}
		log.trace("Sent message: {}", message);

		// Clear receiver id
		message.setReceiverId(0);
		return super.afterSend(message);
	}

	public boolean send(int clientId, Message message) {
		InetSocketAddress client = clients.get(clientId);
		if (client == null) {
			log.warn("Client with id {} not found.", clientId);
			return false;
		}
		message.socketAddressRecipient = client;
		return super.send(message);
	}

	public void unregister(int clientId) {
		log.info("Bye {}", clients.get(clientId));
		clients.remove(clientId);
		lastReceivedMap.remove(clientId);
		config.requiredClients.remove(clientId);
		config.processors.stream().filter(o -> o instanceof IServerHooks).forEach(o1 -> ((IServerHooks) o1).onUnregister(clientId));
		config.serverHooks.onUnregister(clientId);
	}

	public static class Counter {
		public int value;
	}

	private static class NullsafeCounterHashMap extends NullsafeHashMap<Class, Counter> {
		@Override
		protected Counter newInstance() {
			return new Counter();
		}
	}



}
