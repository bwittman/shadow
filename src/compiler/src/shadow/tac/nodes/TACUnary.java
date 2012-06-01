package shadow.tac.nodes;

import java.io.IOException;

import shadow.tac.AbstractTACVisitor;

public class TACUnary extends TACOperation
{
	public static enum Operator implements TACOperator
	{
		PLUS('+'),
		MINUS('-'),
		LOGICAL_NOT('!'),
		BITWISE_NOT('~');
		
		private final char symbol;
		private Operator(char c)
		{
			symbol = c;
		}
		@Override
		public char getSymbol()
		{
			return symbol;
		}

		public boolean isNot()
		{
			return this == LOGICAL_NOT || this == BITWISE_NOT;
		}

		public static Operator parse(char c)
		{
			for (Operator op : Operator.values())
				if (op.getSymbol() == c)
					return op;
			return null;
		}
	}
	
	public TACUnary(Operator unaryOperator, TACNode operand)
	{
		super(unaryOperator, operand);
	}
	
	@Override
	public Operator getOperator()
	{
		return (Operator)super.getOperator();
	}
	
	@Override
	public void accept(AbstractTACVisitor visitor) throws IOException
	{
		visitor.visit(this);
	}
}
