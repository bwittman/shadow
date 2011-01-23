package shadow.TAC.nodes;

import shadow.output.AbstractTACVisitor;

public class TACJoin extends TACNode {
	private TACNode trueExit, falseExit;	/** The true & false exits, only works with simple branches */
	
	public TACJoin(TACNode trueExit, TACNode falseExit) {
		super("JOIN", null);
		this.trueExit = trueExit;
		this.falseExit = falseExit;
		
		if(trueExit != null)
			trueExit.next = this;
		
		if(falseExit != null)
			falseExit.next = this;
		
		parent = null;
	}
	
	public void accept(AbstractTACVisitor visitor) {
		visitor.visit(this);
	}
	
	public void dump(String prefix) {
		System.out.println(prefix + " *JOIN*");
		
		if(next == null)
			return;
		
		if(next instanceof TACJoin) {
			return;
		}
		
		next.dump(prefix);
	}
	
	public TACNode getTrueExit() {
		return trueExit;
	}
	
	public void setTrueExit(TACNode trueExit) {
		this.trueExit = trueExit;
	}

	public TACNode getFalseExit() {
		return falseExit;
	}

	public void setFalseExit(TACNode falseExit) {
		this.falseExit = falseExit;
	}
}