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

package com.jfastnet.messages;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

/** @author Klaus Pfeiffer - klaus@allpiper.com */
@Slf4j
public class AckMessage extends Message implements IBatchable<Long>, IAckMessage {

	/** Message ids to acknowledge. */
	public Collection<Long> batch;

	public AckMessage() {}

	public AckMessage(long id) {
		batch = new HashSet<>();
		batch.add(id);
	}

	@Override
	public void set(Collection<Long> batch) {
		this.batch = batch;
	}

	@Override
	public ReliableMode getReliableMode() {
		return ReliableMode.UNRELIABLE;
	}

	public String toString() {
		return "AckMessage(super=" + super.toString() + ", ids=" + Arrays.toString(batch.toArray()) + ")";
	}

	@Override
	public Collection<Long> getAckIds() {
		return batch;
	}
}
