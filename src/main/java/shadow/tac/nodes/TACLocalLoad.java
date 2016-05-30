package shadow.tac.nodes;

import java.util.HashMap;
import java.util.Map;

import shadow.parser.javacc.ShadowException;
import shadow.tac.TACVisitor;
import shadow.tac.nodes.TACLabelRef.TACLabel;
import shadow.typecheck.type.Modifiers;
import shadow.typecheck.type.Type;

public class TACLocalLoad extends TACOperand {

	private TACVariableRef reference;
	private Map<TACLabel, TACLocalStore> previousStores = new HashMap<TACLabel, TACLocalStore>();

	public TACLocalLoad(TACNode node, TACVariableRef ref)
	{
		super(node);
		reference = ref;
	}

	public TACVariableRef getReference()
	{
		return reference;
	}

	@Override
	public Modifiers getModifiers()
	{
		return reference.getGetType().getModifiers();
	}
	@Override
	public Type getType()
	{
		return reference.getGetType().getType();
	}
	@Override
	public void setType(Type newType)
	{
		reference.getSetType().setType(newType);
	}
	@Override
	public int getNumOperands()
	{
		return 1;
	}
	@Override
	public TACOperand getOperand(int num)
	{
		if (num == 0)
			return reference;
		throw new IndexOutOfBoundsException("" + num);
	}

	@Override
	public void accept(TACVisitor visitor) throws ShadowException
	{
		visitor.visit(this);
	}

	@Override
	public String toString()
	{
		return reference.toString();
	}
	
	public void addPreviousStore(TACLabel label, TACLocalStore store)
	{
		previousStores.put(label, store);
	}
	
	public Map<TACLabel, TACLocalStore> getPreviousStores()
	{
		return previousStores;		
	}
}
