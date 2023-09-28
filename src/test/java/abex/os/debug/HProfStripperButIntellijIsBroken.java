package abex.os.debug;

public class HProfStripperButIntellijIsBroken
{
	// IDEA-220528
	public static void main(String ...args) throws Exception
	{
		HProfStripper.main(args);
	}
}
