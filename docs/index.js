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
import * as fzstd from 'https://cdn.skypack.dev/pin/fzstd@v0.0.3-CqhayxiadQP2aWUj5XHb/mode=imports,min/optimized/fzstd.js';

let el = {};
for (let name of ["file", "container", "info", "openProfiler"]) {
	el[name] = document.getElementById(name);
}

let decompress = (buf, offset, length, type) => {
	let view = new Uint8Array(buf, offset, length);
	let oview = fzstd.decompress(view);
	return new DataView(oview.buffer, oview.byteOffset, oview.byteLength);
}

class Reader {
	static coder = new TextDecoder();
	constructor(view, le, offset) {
		this.dv = view;
		this.le = le;
		this.offset = offset || 0;
	}

	bump(offset) {
		let o = this.offset;
		this.offset += offset;
		return o;
	}

	u32() {
		return this.dv.getUint32(this.bump(4), this.le);
	}
	i32() {
		return this.dv.getInt32(this.bump(4), this.le);
	}
	u64() {
		return Number(this.dv.getBigUint64(this.bump(8), this.le));
	}

	cstr() {
		let start = this.offset;
		for (; this.dv.getUint8(this.offset) != 0; this.offset++);
		let s = new Uint8Array(this.dv.buffer, this.dv.byteOffset + start, this.offset - start);
		this.offset++;
		return Reader.coder.decode(s);
	}
}

const PHASE_INSTANT = 0;
const PHASE_INTERVAL = 1;
const PHASE_INTERVAL_START = 2;
const PHASE_INTERVAL_END = 3

let load = async () => {
	let file = el.file.files[0];
	let buf = await file.arrayBuffer()
	el.container.classList.add("hidden");
	let le = true;
	let header, samples;
	{
		let magic = [..."RP"].map(c => c.charCodeAt(0)).reduce((o, v) => (o << 8) | v, 0);

		let dv = new DataView(buf);
		if (dv.getUint16(0, le) != magic) {
			le = false;
			if (dv.getUint16(0, le) != magic) {
				console.error("magic", dv.getUint16(0, true));
				throw new Error("bad magic");
			}
		}

		let headerLength = Number(dv.getBigUint64(2, le));
		header = decompress(buf, 10, headerLength);
		samples = decompress(buf, 10 + headerLength);
	}

	{
		let h = new Reader(header, le);
		let numSamples = h.u64();
		let µs = h.u64();
		let extraLength = h.u64();
		let numThreads = h.u64();
		let numMethods = h.u64();

		let extra = new Uint8Array(h.dv.buffer, h.dv.byteOffset + h.bump(extraLength), extraLength);
		extra = JSON.parse(Reader.coder.decode(extra));

		let threads = new Array(numThreads);
		for (let i = 0; i< numThreads; i++) {
			threads[i] = h.cstr();
		}
		
		let methods = new Array(numMethods);
		methods[0] = "bug!"
		for (let i = 0; i< numMethods; i++) {
			let id = h.u32();
			let klass = h.cstr();
			klass = klass.substring(1, klass.length - 1);
			let name = h.cstr();
			let signature = h.cstr();
			methods[id] = klass + "::" + name + signature;
		}

		header = {
			numSamples,
			µs,
			extra,
			threads,
			methods,
		}
		console.log(header);
	}

	let blob;
	{
		let r = new Reader(samples, le);

		let categories = [];

		const columnKey = Symbol("columns");
		const columnSetKey = Symbol("columnSet");
		const defaultsKey = Symbol("defaults");
		class Table {
			constructor(...columns) {
				this[columnKey] = columns.map(v => Array.isArray(v) ? v[0] : v);
				this[columnSetKey] = new Set(this[columnKey]);
				this[defaultsKey] = columns.map(v => Array.isArray(v) ? v[1] : 0);
				for (let c of this[columnKey]) {
					this[c] = [];
				}
				Object.defineProperty(this, "length", {
					get: function() {
						return this[this[columnKey][0]].length;
					},
					enumerable: true,
				})
			}

			push(...args) {
				if (args.length == 1 && typeof(args[0]) == "object") {
					for (let i = 0; i < this[columnKey].length; i++) {
						let c = this[columnKey][i];
						let v = args[0][c];
						if (!(c in args[0])) {
							v = this[defaultsKey][i];
						}
						this[c].push(v);
					}
					for (let k of Object.keys(args[0])) {
						if (!this[columnSetKey].has(k)){
							throw new Error(`invalid key ${k}`);
						}
					}
				} else {
					let i = 0;
					for (; i < args.length; i++) {
						this[this[columnKey][i]].push(args[i]);
					}
					for (; i < this[columnKey].length; i++) {
						this[this[columnKey][i]].push(this[defaultsKey][i]);
					}
				}

				return this.length - 1;
			}

			pop() {
				for (let i = 0; i < this[columnKey].length; i++) {
					this[this[columnKey][i]].pop();
				}
			}

			empty(...names) {
				for (let name of names) {
					this[name] = [];
				}

				return this;
			}
		};

		let addCat = (name, color, subs) => {
			let id = categories.length;
			categories.push({
				name,
				color,
				subcategories: [
					"Other", // other must be 0
					...(subs||[])
				],
			});
			return id;
		};
		let cats = {
			java: addCat("Java", "blue"), // must be 0
			blocked: addCat("Blocked", "red"),
			idle: addCat("Idle", "transparent", ["Waiting indefinitely", "Waiting with timeout", "Object wait", "Parked", "Sleeping"]),
			other: addCat("Other", "grey"),
		};

		let malloc = {
			name: "malloc",
			category: "Memory",
			description: "Allocated memory",
			pid: 1,
			mainThreadIndex: 0,
			sampleGroups: [
				{
					id: 0,
					samples: new Table("time", "number", "count"),
				}
			],
		};
		malloc.sampleGroups[0].samples.push(1, 1, 1);

		class Thread {
			constructor(name) {
				this.name = name;
				this.stackTable = new Table("frame", "prefix", "category", "subcategory");
				this.samples = new Table("stack", "time", "eventDelay");
				this.frameTable = new Table("func", "category", "subcategory")
					.empty("address", "nativeSymbol", "innerWindowID", "implementation", "line", "column", "optimizations");
				this.funcTable = new Table("name", "resource", ["fileName", null]).empty("isJS", "relevantForJS", "lineNumber", "columnNumber");
				this.markers = new Table("data", "name", "startTime", "endTime", "phase", "category");
				this.resourceTable = new Table("name", "type").empty("lib", "host");
				this.stringArray = [];

				this.time = 0;

				this.stacks = new Map();
				this.frames = new Map();
				this.strings = new Map();

				this.resourceTable.push(this.getStringID(""), 2);
			}

			toJSON() {
				return {
					name: this.name == "Client" ? "GeckoMain" : this.name,
					processName: "Client",
					pid: 1,
					libs: [],
					pausedRanges: [],
					frameTable: this.frameTable,
					funcTable: this.funcTable,

					stackTable: this.stackTable,
					samples: this.samples,
					markers: this.markers,
					resourceTable: this.resourceTable,
					stringArray: this.stringArray,
				};
			}

			getStackID(key, frames, depth) {
				let id = this.stacks.get(key);
				if (id === undefined) {
					let prefix = depth == 0 ? null : this.getStackID(key >> 32n, frames, depth - 1);
					let methodID = frames[depth];
					this.stacks.set(key, id = this.stackTable.push({
						frame: this.getFrameID(methodID),
						prefix,
						...this.unpackCat(methodID),
					}));
				}
				return id;
			}

			getFrameID(methodID) {
				let id = this.frames.get(methodID)
				if (id === undefined) {
					let {category, subcategory} = this.unpackCat(methodID);
					let func = this.funcTable.push({
						name: this.getStringID(category ? categories[category].name : header.methods[methodID]),
					});
					this.frames.set(methodID, id = this.frameTable.push(
						func, category, subcategory,
					));
				}
				return id;
			}

			getStringID(string) {
				let id = this.strings.get(string);
				if (id === undefined) {
					id = this.stringArray.length;
					this.strings.set(string, id);
					this.stringArray.push(string);
				}
				return id;
			}

			unpackCat(methodID) {
				let category = 0, subcategory = 0;
				if ((methodID & 0x4000_0000) != 0) {
					category = methodID & 0xFFFF;
					subcategory = (methodID >>> 16) & 0x3FFF;
				}
				return {category, subcategory};
			}
		}

		let threads = header.threads.map(name => new Thread(name));

		let eventTime = 0;
		function readTime() {
			return eventTime + (r.i32() / 1_000_000);
		}

		let memoryUsedLast = 0;

		let frames = new Uint32Array(0xffff);
		for (let s = 0; s < header.numSamples; s++) {
			{
				let deltaTimeNs = r.u32();
				let deltaTimeMs = deltaTimeNs / 1_000_000;

				{
					let heapUsed = r.u64();
					let heapCommit = r.u64();
					let offheapUsed = r.u64();
					let offheapCommit = r.u64();

					let total = heapUsed; + offheapUsed;

					if (memoryUsedLast != total) {
						malloc.sampleGroups[0].samples.push({
							time: threads[0].time,
							number: 1,
							count: total - memoryUsedLast,
						});
						memoryUsedLast = total;
					}
				}

				for (let t = 0; t < header.threads.length; t++) {
					let thread = threads[t];

					let state = r.u32();
					let numFrames = r.u32();
					if (frames.length < numFrames) {
						frames = new Uint32Array(frames.length + 1);
					}
					let key = 0n;
					for (let i = numFrames - 1; i >= 0; i--) {
						let frame = r.u32();
						frames[i] = frame;
						key |= BigInt(frame) << BigInt(32 * i);
					}
					let cat = cats.other;
					if (state & 0x04) {
						// runnable
						cat = 0;
					} else if (state & 0x400) {
						// blocked on monitorenter
						cat = cats.blocked;
					}else if (state & 0x80) {
						// waiting
						cat = cats.idle;
						if (state & 0x10) {
							// indefinite
							cat |= 1 << 16;
						} else if (state & 0x20) {
							// with timeout
							cat |= 2 << 16;
						} else if (state & 0x100) {
							// object wait
							cat |= 3 << 16;
						} else if (state & 0x200) {
							// parked
							cat |= 4 << 16;
						} else if (state & 0x40) {
							// sleeping
							cat |= 5 << 16;
						}
					}
					if (cat != 0) {
						let frame = 0x4000_0000 | cat;
						frames[numFrames++] = frame;
						key = (key << 32n) | BigInt(frame);
					}
					let location = r.u32();

					let stackID = thread.getStackID(key, frames, numFrames - 1, state);
					thread.samples.push(stackID, thread.time += deltaTimeMs, .0001);
				}
			}
			{
				let deltaTimeNs = r.u32();
				let deltaTimeMs = deltaTimeNs / 1_000_000;
				let thread = threads[0];
				let markers = thread.markers;
				eventTime += deltaTimeMs;
				for (;;) {
					let type = r.u32();
					if (type == 0) {
						break;
					}
					switch (type) {
						case 1: { // GC
							let startTime = readTime();
							let endTime = readTime();
							markers.push({
								name: thread.getStringID("GC"),
								startTime,
								endTime,
								phase: PHASE_INTERVAL,
								category: 0,
							});
							break;
						}
						case 0x10001: {
							let startTime = readTime();
							let state = r.u32();
							state = {
								10: "Login screen",
								11: "Login screen authenticator",
								20: "Logging in",
								25: "Loading",
								30: "Logged in",
								40: "Connection lost",
								50: "Hopping",
							}[state] || state;
							markers.push({
								name: thread.getStringID("Game State " + state),
								startTime,
								phase: PHASE_INSTANT,
								category: 0,
							});
							break;
						}
						case 0x10002: {
							let startTime = readTime();
							markers.push({
								name: thread.getStringID("GameTick"),
								startTime,
								phase: PHASE_INSTANT,
								category: 0,
							});
							break;
						}
						default:
							throw new Error(`unknown type ${type}`);
					}
				}
			}
		}

		let data = {
			libs: [],
			meta: {
				version: 23,
				startTime: Date.now(),
				preprocessedProfileVersion: 36,
				misc: header.extra.version,
				product: "RuneLite",
				oscpu: header.extra["os.name"] + " " + header.extra["os.arch"],
				abi: header.extra["os.arch"],
				appBuildID: header.extra.buildID,
				interval: header.extra["delay"] / 1_000,
				categories,
				markerSchema: [],
				sampleUnits: {
					time: "ms",
					eventDelay: "ms",
					//threadCPUDelta: "ns",
				},
			},
			pages: [],
			counters: [
				malloc,
			],
			// profiler overhead
			threads: threads.map(t => t.toJSON()),
		};
		console.log(data);
		blob = new Blob([JSON.stringify(data)])
	}

	for (; el.info.firstChild; el.info.remove(el.info.lastChild));
	for (let [key, value] of Object.entries(header.extra)) {
		el.info.appendChild(new Text(`${key}: ${value}`));
		el.info.appendChild(document.createElement("br"));
	}
	{
		let url = URL.createObjectURL(blob);
		el.openProfiler.href = url;
		el.openProfiler.download = file.name + ".gecko_profile.json";

		// ideally we could do this but csp & cors prevent this ;-;
		//el.openProfiler.href = "https://profiler.firefox.com/from-url/" + encodeURIComponent(url);
	}
	el.container.classList.remove("hidden");
}

el.file.addEventListener("change", ev => load());
if (el.file.files.length > 0) {
	console.log("loading last");
	load();
}