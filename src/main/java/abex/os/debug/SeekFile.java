package abex.os.debug;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class SeekFile extends DataInputStream
{
	private final Input input;

	public SeekFile(File file) throws IOException
	{
		this(new Input(file));
	}

	private SeekFile(Input input)
	{
		super(input);
		this.input = input;
	}

	private static class Input extends InputStream
	{
		private static final int PAGE_MASK = 4095;
		private final RandomAccessFile raf;
		private long rafPtr;
		private long end;
		private final byte[] page = new byte[PAGE_MASK + 1];
		private long pagePtr = -1;
		private long ptr = 0;

		Input(File file) throws IOException
		{
			raf = new RandomAccessFile(file, "r");
			end = raf.length();
		}

		@Override
		public int read() throws IOException
		{
			buffer();
			if (ptr >= end)
			{
				return -1;
			}
			return page[(int) ptr++ & PAGE_MASK] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException
		{
			if (ptr >= end)
			{
				return -1;
			}

			if (bufAvailable() < 0 && len >= PAGE_MASK)
			{
				if (rafPtr != ptr)
				{
					raf.seek(ptr);
					rafPtr = ptr;
				}
				int read = raf.read(b, off, (len / page.length) * page.length);
				if (read > 0)
				{
					rafPtr += read;
				}
				return read;
			}
			buffer();

			int read = Math.min(len, bufAvailable());
			System.arraycopy(page, (int) ptr & PAGE_MASK, b, off, read);
			ptr += read;
			return read;
		}

		@Override
		public long skip(long n) throws IOException
		{
			long start = ptr;
			ptr = Math.min(ptr + n, end);
			return ptr - start;
		}

		@Override
		public int available() throws IOException
		{
			return (int) Math.min(Integer.MAX_VALUE, end - ptr);
		}

		@Override
		public long transferTo(OutputStream out) throws IOException
		{
			return copyTo(out, end - ptr);
		}

		public long copyTo(OutputStream out, long bytes) throws IOException
		{
			long start = ptr;
			long end = Math.min(this.end, ptr + bytes);
			for (; ptr < end; )
			{
				buffer();
				int read = Math.min((int) (end - ptr), bufAvailable());
				out.write(page, (int) ptr & PAGE_MASK, read);
				ptr += read;
			}
			return ptr - start;
		}

		private void buffer() throws IOException
		{
			if (pagePtr == (ptr & ~PAGE_MASK))
			{
				return;
			}
			pagePtr = ptr & ~PAGE_MASK;
			if (rafPtr != pagePtr)
			{
				raf.seek(pagePtr);
				rafPtr = pagePtr;
			}

			int fill = 0;
			for (; fill < page.length && rafPtr < end; )
			{
				int read = raf.read(page, fill, page.length - fill);
				if (read < 0)
				{
					throw new EOFException();
				}
				fill += read;
				rafPtr += read;
			}
		}

		private int bufAvailable()
		{
			long bufEnd = Math.min(pagePtr + page.length, end);
			return (int) (bufEnd - ptr);
		}

		@Override
		public void close() throws IOException
		{
			super.close();
		}
	}

	public void seek(long to)
	{
		input.ptr = to;
	}

	public long offset()
	{
		return input.ptr;
	}

	public void copyTo(OutputStream out, long bytes) throws IOException
	{
		input.copyTo(out, bytes);
	}

	@Override
	public void close() throws IOException
	{
		super.close();
	}

	public long readU4() throws IOException
	{
		return readInt() & 0xFFFF_FFFFL;
	}

	public long length()
	{
		return input.end;
	}
}
