package abex.os.debug;

import java.util.*;

// this is a slightly modified, chatgpt converted version of a scala script https://gist.github.com/yuzeh/a5e6602dfdb0db3c2130c10537db54d7
// which is taken from "Modern Compiler Implementation in Java", 2nd ed. chapter 19.2
public class LengauerTarjan
{
	int nodeCount;
	int root;
	int[][] succs;
	List<Integer>[] preds;

	public LengauerTarjan(int nodeCount, int root, int[][] succs)
	{
		this.nodeCount = nodeCount;
		this.root = root;
		this.succs = succs;
		buildPreds();
	}

	private void buildPreds()
	{
		preds = new ArrayList[nodeCount];
		for (int i = 0; i < nodeCount; i++)
		{
			preds[i] = new ArrayList<>();
		}
		for (int v = 0; v < nodeCount; v++)
		{
			for (int p : succs[v])
			{
				// v -> p
				preds[p].add(v);
			}
		}
	}

	public int[] computeIdom()
	{
		int nNodes = nodeCount;
		int N = 0;

		@SuppressWarnings("unchecked")
		Set<Integer>[] bucket = new Set[nNodes];
		for (int i = 0; i < nNodes; i++)
		{
			bucket[i] = new HashSet<>();
		}

		int[] dfnum = new int[nNodes];
		Arrays.fill(dfnum, 0);
		int[] vertex = new int[nNodes];
		Arrays.fill(vertex, -1);
		int[] parent = new int[nNodes];
		Arrays.fill(parent, -1);
		int[] semi = new int[nNodes];
		Arrays.fill(semi, -1);
		int[] ancestor = new int[nNodes];
		Arrays.fill(ancestor, -1);
		int[] idom = new int[nNodes];
		Arrays.fill(idom, -1);
		int[] samedom = new int[nNodes];
		Arrays.fill(samedom, -1);
		int[] best = new int[nNodes];
		Arrays.fill(best, -1);

		// DFS to number nodes
		Deque<int[]> stack = new ArrayDeque<>();
		stack.push(new int[]{-1, root}); // parent, node
		while (!stack.isEmpty())
		{
			int[] pair = stack.pop();
			int p = pair[0], v = pair[1];
			if (dfnum[v] == 0)
			{
				dfnum[v] = N;
				vertex[N] = v;
				parent[v] = p;
				N++;
				for (int w : succs[v])
				{
					stack.push(new int[]{v, w});
				}
			}
		}

		// Helper: ancestor with lowest semi
		final int[] dfnumRef = dfnum;
		final int[] semiRef = semi;
		final int[] ancestorRef = ancestor;
		final int[] bestRef = best;

		class Helper
		{
			int ancestorWithLowestSemi(int v)
			{
				int a = ancestorRef[v];
				if (a >= 0 && ancestorRef[a] >= 0)
				{
					int b = ancestorWithLowestSemi(a);
					ancestorRef[v] = ancestorRef[a];
					if (dfnumRef[semiRef[b]] < dfnumRef[semiRef[bestRef[v]]])
					{
						bestRef[v] = b;
					}
				}
				return bestRef[v];
			}
		}

		Helper helper = new Helper();

		// Link function
		class Linker
		{
			void link(int p, int n)
			{
				ancestor[n] = p;
				best[n] = n;
			}
		}
		Linker linker = new Linker();

		// Main computation
		for (int i = N - 1; i >= 1; i--)
		{
			int n = vertex[i];
			int p = parent[n];
			int s = p;

			for (int v : preds[n])
			{
				int sPrime = dfnum[v] <= dfnum[n] ? v : semi[helper.ancestorWithLowestSemi(v)];
				if (dfnum[sPrime] < dfnum[s])
				{
					s = sPrime;
				}
			}

			semi[n] = s;
			bucket[s].add(n);
			linker.link(p, n);

			for (int v : bucket[p])
			{
				int y = helper.ancestorWithLowestSemi(v);
				if (semi[y] == semi[v])
				{
					idom[v] = p;
				}
				else
				{
					samedom[v] = y;
				}
			}
			bucket[p].clear();
		}

		for (int i = 1; i < N; i++)
		{
			int n = vertex[i];
			if (samedom[n] >= 0)
			{
				idom[n] = idom[samedom[n]];
			}
		}

		return idom;
	}
}
