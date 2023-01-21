/*
 * Copyright (c) 2021 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

export class AbstractHashMap {
	constructor() {
		this.backing = new Map()
	}

	hash(key) {
		throw new Error("abstract");
	}
	equals(a, b) {
		throw new Error("abstract");
	}
	beforeInsert(key) {
		return key;
	}

	set(key, value) {
		let hash = this.hash(key);
		let v = this.backing.get(hash);
		if (v === undefined) {
			this.backing.set(hash, v = []);
		}

		for (let i = 0; i < v.length; i += 2) {
			if (this.equals(v[i], key)) {
				let old = v[i + 1];
				v[i + 1] = value;
				return old;
			}
		}

		v.push(this.beforeInsert(key), value);
		return null;
	}

	get(key) {
		let hash = this.hash(key);
		let v = this.backing.get(hash);
		if (v === undefined) {
			return;
		}

		for (let i = 0; i < v.length; i += 2) {
			if (this.equals(v[i], key)) {
				return v[i + 1];
			}
		}
	}

	computeIfAbsent(key, compute) {
		let hash = this.hash(key);
		let v = this.backing.get(hash);
		if (v === undefined) {
			this.backing.set(hash, v = []);
		}

		for (let i = 0; i < v.length; i += 2) {
			if (this.equals(v[i], key)) {
				return v[i + 1];
			}
		}

		let value = compute(key);
		v.push(this.beforeInsert(key), value);
		return value;
	}
};

export class NumberArrayKeyedMap extends AbstractHashMap {
	constructor() {
		super(...arguments);
	}

	hash(key) {
		let hash = 1;
		for (let v of key) {
			hash = ((hash * 31)|0 + v)|0;
		}
		return hash;
	}

	equals(a, b) {
		if (a.length != b.length) {
			return false;
		}
		for (let i = 0; i < a.length; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}

	beforeInsert(key) {
		return key.slice();
	}
}