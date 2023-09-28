package abex.os.debug;

import java.io.IOException;
import java.io.OutputStream;

public class ZstdOutputStream extends OutputStream
{
	private static final int ZSTD_E_CONTINUE = 0;
	private static final int ZSTD_E_FLUSH = 1;
	private static final int ZSTD_E_END = 2;

	private final OutputStream out;
	private final byte[] outBuf;
	private final byte[] inBuf;
	private int inBufPtr;
	private long stream;

	public static void init()
	{
		// libprofiler uses zstd too, so we just reuse that
		Profiler.init();
	}

	public ZstdOutputStream(OutputStream out, int level)
	{
		this.out = out;
		this.outBuf = new byte[cStreamOutSize()];
		this.inBuf = new byte[cStreamInSize()];
		this.stream = new0(level);
		if (this.stream == 0)
		{
			throw new RuntimeException();
		}
	}

	@Override
	public void write(int b) throws IOException
	{
		if (inBufPtr + 1 >= inBuf.length)
		{
			flushBuffer(ZSTD_E_CONTINUE);
		}

		inBuf[inBufPtr++] = (byte) b;
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException
	{
		if (len >= 32)
		{
			flushBuffer(ZSTD_E_CONTINUE);
			compress(b, off, len, ZSTD_E_CONTINUE);
		}
		else
		{
			int size = Math.min(len, inBuf.length - inBufPtr);
			System.arraycopy(b, off, inBuf, inBufPtr, size);
			inBufPtr += size;
			if (size != len)
			{
				off += size;
				len = len - size;
				System.arraycopy(b, off, inBuf, inBufPtr, len);
			}
		}
	}

	@Override
	public void flush() throws IOException
	{
		flushBuffer(ZSTD_E_FLUSH);
		out.flush();
	}

	@Override
	public void close() throws IOException
	{
		flushBuffer(ZSTD_E_END);
		free();
		out.close();
	}

	public void free()
	{
		free0(stream);
		stream = 0;
	}

	private void flushBuffer(int endOp) throws IOException
	{
		compress(inBuf, 0, inBufPtr, endOp);
		inBufPtr = 0;
	}

	private void compress(byte[] buf, int off, int len, int endOp) throws IOException
	{
		for (; ; )
		{
			long res = compress0(stream, buf, off, len, outBuf, endOp);
			int wr = (int) (res >> 32);
			off += wr;
			len -= wr;
			int outLen = ((int) res) & 0x7FFF_FFFF;
			if (outLen > 0)
			{
				out.write(outBuf, 0, outLen);
			}
			boolean more = ((res >> 31) & 1) != 0;
			if (len > 0 || (endOp != ZSTD_E_CONTINUE && more))
			{
				continue;
			}
			break;
		}
	}

	private static native int cStreamInSize();

	private static native int cStreamOutSize();

	private static native long new0(int level);

	private static native void free0(long stream);

	private static native long compress0(long stream, byte[] bytes, int off, int len, byte[] out, int endOp) throws IOException;
}
