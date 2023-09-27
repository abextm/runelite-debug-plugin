package abex.os.debug;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;

public class HProfStripper
{
	// top-level records
	private final static int
		HPROF_UTF8 = 0x01,
		HPROF_LOAD_CLASS = 0x02,
		HPROF_UNLOAD_CLASS = 0x03,
		HPROF_FRAME = 0x04,
		HPROF_TRACE = 0x05,
		HPROF_ALLOC_SITES = 0x06,
		HPROF_HEAP_SUMMARY = 0x07,
		HPROF_START_THREAD = 0x0A,
		HPROF_END_THREAD = 0x0B,
		HPROF_HEAP_DUMP = 0x0C,
		HPROF_CPU_SAMPLES = 0x0D,
		HPROF_CONTROL_SETTINGS = 0x0E,

	// 1.0.2 record types
	HPROF_HEAP_DUMP_SEGMENT = 0x1C,
		HPROF_HEAP_DUMP_END = 0x2C,

	// field types
	HPROF_ARRAY_OBJECT = 0x01,
		HPROF_NORMAL_OBJECT = 0x02,
		HPROF_BOOLEAN = 0x04,
		HPROF_CHAR = 0x05,
		HPROF_FLOAT = 0x06,
		HPROF_DOUBLE = 0x07,
		HPROF_BYTE = 0x08,
		HPROF_SHORT = 0x09,
		HPROF_INT = 0x0A,
		HPROF_LONG = 0x0B,

	// data-dump sub-records
	HPROF_GC_ROOT_UNKNOWN = 0xFF,
		HPROF_GC_ROOT_JNI_GLOBAL = 0x01,
		HPROF_GC_ROOT_JNI_LOCAL = 0x02,
		HPROF_GC_ROOT_JAVA_FRAME = 0x03,
		HPROF_GC_ROOT_NATIVE_STACK = 0x04,
		HPROF_GC_ROOT_STICKY_CLASS = 0x05,
		HPROF_GC_ROOT_THREAD_BLOCK = 0x06,
		HPROF_GC_ROOT_MONITOR_USED = 0x07,
		HPROF_GC_ROOT_THREAD_OBJ = 0x08,
		HPROF_GC_CLASS_DUMP = 0x20,
		HPROF_GC_INSTANCE_DUMP = 0x21,
		HPROF_GC_OBJ_ARRAY_DUMP = 0x22,
		HPROF_GC_PRIM_ARRAY_DUMP = 0x23;

	private int[] typeSizes;

	private static final byte[] EXPECTED_HEADER = "JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8);

	private final SeekFile in;

	private final DataOutputStream out;

	private long start;
	private int identSize;

	public HProfStripper(File in, File out) throws IOException
	{
		this.in = new SeekFile(in);
		var fos = new FileOutputStream(out);
		var gzo = new GZIPOutputStream(fos)
		{
			{
				this.def.setLevel(3);
			}
		};// */
		this.out = new DataOutputStream(new BufferedOutputStream(gzo));
	}

	public void run() throws IOException
	{
		try (this.in; this.out)
		{
			{
				byte[] header = new byte[EXPECTED_HEADER.length];
				in.readFully(header);
				if (!Arrays.equals(header, EXPECTED_HEADER))
				{
					throw new IOException("incorrect header " + Arrays.toString(header));
				}
			}
			out.write(EXPECTED_HEADER);

			identSize = in.readInt();
			out.writeInt(identSize);

			typeSizes = new int[]{-1, identSize, identSize, -1, 1, 2, 4, 8, 1, 2, 4, 8};

			// ts
			out.writeInt(in.readInt());
			out.writeInt(in.readInt());

			start = in.offset();

			var fk = new FindKeepers();
			fk.run();
			var emit = new Emit(fk.keepObjects);
			emit.run();
			//emit.sizes.forEach((k, v) -> System.out.println(k + " " + (v / 1024) + "kiB"));
		}
	}

	protected long readId() throws IOException
	{
		if (identSize == 4)
		{
			return in.readU4();
		}
		else if (identSize == 8)
		{
			return in.readLong();
		}
		throw new UnsupportedOperationException("" + identSize);
	}

	protected void writeId(long id) throws IOException
	{
		if (identSize == 4)
		{
			out.writeInt((int) id);
		}
		else if (identSize == 8)
		{
			out.writeLong(id);
		}
		else
		{
			throw new UnsupportedOperationException("" + identSize);
		}
	}

	private class DumpVisitor
	{
		public void run() throws IOException
		{
			try
			{
				in.seek(start);
				for (; in.offset() != in.length(); )
				{
					int tag = in.readByte();
					int ts = in.readInt();
					int bytes = in.readInt();
					section(tag, ts, bytes);
				}
			}
			catch (Exception e)
			{
				throw new IOException(e.getMessage() + " @ " + in.offset(), e);
			}
		}

		protected void section(int tag, int ts, int bytes) throws IOException
		{
			//System.out.println("sect " + tag + " " + in.offset() + " " + (in.offset() + bytes));
			if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT)
			{
				long end = in.offset() + bytes;
				for (; in.offset() < end; )
				{
					int dtag = in.readByte();
					long id = readId();
					//System.out.println(dtag + " " + id + " " + in.offset());
					readTag(dtag, id);
				}
				return;
			}
			unknownSection(tag, ts, bytes);
		}

		protected void unknownSection(int tag, int ts, int bytes) throws IOException
		{
			skip(bytes);
		}

		protected void readTag(int tag, long id) throws IOException
		{
			switch (tag)
			{
				case HPROF_GC_ROOT_UNKNOWN:
					break;
				case HPROF_GC_ROOT_THREAD_OBJ:
				case HPROF_GC_ROOT_JNI_LOCAL:
					skip(4 + 4);
					break;
				case HPROF_GC_ROOT_JNI_GLOBAL:
					skip(identSize);
					break;
				case HPROF_GC_ROOT_JAVA_FRAME:
					skip(4 + 4);
					break;
				case HPROF_GC_ROOT_NATIVE_STACK:
					skip(4);
					break;
				case HPROF_GC_ROOT_STICKY_CLASS:
					break;
				case HPROF_GC_ROOT_THREAD_BLOCK:
					skip(4);
					break;
				case HPROF_GC_ROOT_MONITOR_USED:
					break;
				case HPROF_GC_CLASS_DUMP:
				{
					skip(4 + identSize + identSize + identSize + identSize + identSize + identSize + 4);
					int cpsize = in.readUnsignedShort();
					if (cpsize != 0)
					{
						throw new IllegalArgumentException();
					}
					int numStatics = in.readUnsignedShort();
					for (int i = 0; i < numStatics; i++)
					{
						skip(identSize);
						int ty = in.readByte();
						skip(typeSizes[ty]);
					}
					int numInsts = in.readUnsignedShort();
					skip(numInsts * (identSize + 1));
					break;
				}
				case HPROF_GC_INSTANCE_DUMP:
					skip(4 + identSize);
					skip(in.readInt());
					break;
				case HPROF_GC_OBJ_ARRAY_DUMP:
				{
					skip(4);
					int count = in.readInt();
					skip(identSize);
					skip(count * identSize);
					break;
				}
				case HPROF_GC_PRIM_ARRAY_DUMP:
				{
					skip(4);
					int count = in.readInt();
					int type = in.readByte();
					skip(count * typeSizes[type]);
					break;
				}
				default:
					throw new IllegalArgumentException(tag + "@" + in.offset());
			}
		}
	}

	@RequiredArgsConstructor
	private static class ClassMetadata
	{
		final long parent;
		final byte[] types;
	}

	private class FindKeepers extends DumpVisitor
	{
		int pass = 0;
		Map<Long, ClassMetadata> keepObjects = new HashMap<>();
		Map<Long, Integer> recursiveKeepObjects = new HashMap<>();
		boolean added;

		@Override
		public void run() throws IOException
		{
			for (pass = 0; pass == 0 || (recursiveKeepObjects.size() > 0 && added); pass++)
			{
				added = false;
				super.run();
				//System.out.println(pass + " " + recursiveKeepObjects.size() + " " + added);
			}
		}

		@Override
		protected void section(int tag, int ts, int bytes) throws IOException
		{
			if (tag == HPROF_UTF8)
			{
				long id = readId();
				recursiveKeepObjects.remove(id);
				keepObjects.putIfAbsent(id, null);
				skip(bytes - identSize);
				return;
			}

			if (pass == 0)
			{
				if (tag == HPROF_LOAD_CLASS)
				{
					skip(4 + identSize + 4);
					keep(readId(), 2); // class name
					return;
				}
				if (tag == HPROF_FRAME)
				{
					skip(identSize);
					keep(readId(), 1);
					keep(readId(), 1);
					keep(readId(), 1);
					skip(4 + 4);
					return;
				}
				if (tag == HPROF_START_THREAD)
				{
					skip(4 + identSize + 4);
					keep(readId(), 1);
					keep(readId(), 1);
					keep(readId(), 1);
					return;
				}
			}

			super.section(tag, ts, bytes);
		}

		@Override
		protected void readTag(int tag, long id) throws IOException
		{
			Integer keep = recursiveKeepObjects.remove(id);
			if (keep != null)
			{
				keepObjects.putIfAbsent(id, null);
			}
			switch (tag)
			{
				case HPROF_GC_INSTANCE_DUMP:
					if (keep != null)
					{
						skip(4);
						long clazz = readId();
						int size = in.readInt();
						long end = in.offset() + size;
						// strings or smaller
						int sizeClass = size > 5 + identSize ? 1 : 0;

						for (; clazz != 0; )
						{
							ClassMetadata meta = keepObjects.get(clazz);
							if (meta == null)
							{
								recursiveKeepObjects.put(id, keep);
								break;
							}
							for (byte ty : meta.types)
							{
								if (ty == HPROF_ARRAY_OBJECT || ty == HPROF_NORMAL_OBJECT)
								{
									keep(readId(), keep - sizeClass);
								}
								else
								{
									skip(typeSizes[ty]);
								}
							}
							clazz = meta.parent;
						}

						skip((int) (end - in.offset()));
						return;
					}
					break;
				case HPROF_GC_OBJ_ARRAY_DUMP:
					if (keep != null)
					{
						skip(4);
						int count = in.readInt();
						skip(identSize);
						for (int i = 0; i < count; i++)
						{
							keep(readId(), keep - 1);
						}
						return;
					}
					break;
				case HPROF_GC_CLASS_DUMP:
					if (pass == 0)
					{
						skip(4);
						long superclass = readId();
						long cl = readId();
						keep(cl, 3);
						readId();
						readId();
						readId();
						readId();
						in.readInt();
						int cpsize = in.readUnsignedShort();
						if (cpsize != 0)
						{
							throw new IllegalArgumentException();
						}
						int numStatics = in.readUnsignedShort();
						for (int i = 0; i < numStatics; i++)
						{
							long name = readId();
							keep(name, 2);
							int ty = in.readByte();
							skip(typeSizes[ty]);
						}
						int numInsts = in.readUnsignedShort();
						byte[] types = new byte[numInsts];
						for (int i = 0; i < numInsts; i++)
						{
							long name = readId();
							byte ty = in.readByte();
							keep(name, 2);
							types[i] = ty;
						}
						keepObjects.put(id, new ClassMetadata(superclass, types));
						return;
					}
					break;
			}

			super.readTag(tag, id);
		}

		private void keep(long id, int depth)
		{
			if (depth <= 0 || id == 0)
			{
				return;
			}

			Long id0 = id;
			if (!keepObjects.containsKey(id0))
			{
				added |= recursiveKeepObjects.compute(id0, (_k, v) -> Math.max(v == null ? 0 : v, depth)) == depth;
			}
		}
	}


	@RequiredArgsConstructor
	private class Emit extends DumpVisitor
	{
		final Map<Long, ClassMetadata> keepObjects;
		final Map<Integer, Long> sizes = new HashMap<>();

		@Override
		protected void section(int tag, int ts, int bytes) throws IOException
		{
			out.writeByte(tag);
			out.writeInt(ts);
			out.writeInt(bytes);

			sizes.compute(tag, (_k, v) -> bytes + (v == null ? 0 : v));

			if (tag == HPROF_UTF8)
			{
				long id = readId();
				writeId(id);
				int rem = bytes - identSize;
				if (keepObjects.containsKey(id))
				{
					in.copyTo(out, rem);
				}
				else
				{
					zero(rem);
				}
			}
			else
			{
				super.section(tag, ts, bytes);
			}
		}

		@Override
		protected void unknownSection(int tag, int ts, int bytes) throws IOException
		{
			in.copyTo(out, bytes);
		}

		@Override
		protected void readTag(int tag, long obj) throws IOException
		{
			out.writeByte(tag);
			writeId(obj);

			switch (tag)
			{
				case HPROF_GC_ROOT_UNKNOWN:
					break;
				case HPROF_GC_ROOT_THREAD_OBJ:
					out.writeInt(in.readInt());
					out.writeInt(in.readInt());
					break;
				case HPROF_GC_ROOT_JNI_GLOBAL:
					writeId(readId());
					break;
				case HPROF_GC_ROOT_JNI_LOCAL:
					out.writeInt(in.readInt());
					out.writeInt(in.readInt());
					break;
				case HPROF_GC_ROOT_JAVA_FRAME:
					out.writeInt(in.readInt());
					out.writeInt(in.readInt());
					break;
				case HPROF_GC_ROOT_NATIVE_STACK:
					out.writeInt(in.readInt());
					break;
				case HPROF_GC_ROOT_STICKY_CLASS:
					break;
				case HPROF_GC_ROOT_THREAD_BLOCK:
					out.writeInt(in.readInt());
					break;
				case HPROF_GC_ROOT_MONITOR_USED:
					break;
				case HPROF_GC_CLASS_DUMP:
				{
					in.copyTo(out, 4 + identSize + identSize + identSize + identSize + identSize + identSize + 4);
					int cpsize = in.readUnsignedShort();
					out.writeShort(cpsize);
					if (cpsize != 0)
					{
						throw new IllegalArgumentException();
					}
					int numStatics = in.readUnsignedShort();
					out.writeShort(numStatics);
					for (int i = 0; i < numStatics; i++)
					{
						writeId(readId());
						int ty = in.readByte();
						out.writeByte(ty);
						if (ty == HPROF_ARRAY_OBJECT || ty == HPROF_NORMAL_OBJECT)
						{
							writeId(readId());
						}
						else
						{
							in.copyTo(out, typeSizes[ty]);
						}
					}
					int numInsts = in.readUnsignedShort();
					out.writeShort(numInsts);
					in.copyTo(out, numInsts * (identSize + 1L));
					break;
				}
				case HPROF_GC_INSTANCE_DUMP:
				{
					out.writeInt(in.readInt());
					long clazz = readId();
					writeId(clazz);
					int size = in.readInt();
					out.writeInt(size);
					if (keepObjects.containsKey(obj))
					{
						in.copyTo(out, size);
					}
					else
					{
						for (; clazz != 0; )
						{
							ClassMetadata meta = keepObjects.get(clazz);
							for (byte ty : meta.types)
							{
								if (ty == HPROF_ARRAY_OBJECT || ty == HPROF_NORMAL_OBJECT)
								{
									writeId(readId());
								}
								else
								{
									zero(typeSizes[ty]);
								}
							}
							clazz = meta.parent;
						}
					}
					break;
				}
				case HPROF_GC_OBJ_ARRAY_DUMP:
				{
					out.writeInt(in.readInt());
					int count = in.readInt();
					out.writeInt(count);
					in.copyTo(out, identSize + ((long) count * identSize));
					break;
				}
				case HPROF_GC_PRIM_ARRAY_DUMP:
				{
					out.writeInt(in.readInt());
					int count = in.readInt();
					out.writeInt(count);
					int type = in.readByte();
					out.writeByte(type);
					int size = count * typeSizes[type];
					if (keepObjects.containsKey(obj))
					{
						in.copyTo(out, size);
					}
					else
					{
						zero(size);
					}
					break;
				}
				default:
					throw new IllegalArgumentException("" + tag);
			}

			assert out.size() == (int) Math.min(Integer.MAX_VALUE, in.offset()) : tag;
		}
	}

	private final byte[] zero = new byte[256];

	private void zero(int bytes) throws IOException
	{
		skip(bytes);
		for (; bytes > 0; )
		{
			int chunk = Math.min(zero.length, bytes);
			out.write(zero, 0, chunk);
			bytes -= chunk;
		}
	}

	private void skip(int bytes) throws IOException
	{
		for (; bytes > 0; )
		{
			int read = in.skipBytes(bytes);
			if (read < 0)
			{
				throw new EOFException();
			}
			bytes -= read;
		}
	}

	public static void main(String... args) throws IOException
	{
		new HProfStripper(new File(args[0]), new File(args[1])).run();
	}
}
