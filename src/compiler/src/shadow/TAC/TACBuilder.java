package shadow.TAC;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import shadow.AST.ASTUtils;
import shadow.AST.ASTWalker;
import shadow.AST.ASTWalker.WalkType;
import shadow.AST.AbstractASTVisitor;
import shadow.parser.javacc.ASTClassOrInterfaceDeclaration;
import shadow.parser.javacc.Node;
import shadow.parser.javacc.ShadowException;
import shadow.parser.javacc.ShadowParser.ModifierSet;
import shadow.parser.javacc.SimpleNode;
import shadow.typecheck.MethodSignature;
import shadow.typecheck.type.ClassInterfaceBaseType;

/*
 * This class is a total hack right now
 */
public class TACBuilder extends AbstractASTVisitor {
	private boolean debug;
	private LinkedList<TACClass> classes;
	
	public TACBuilder(boolean debug) {
		classes = new LinkedList<TACClass>();
		this.debug = debug;
	}
	
	public void build(Node node) throws ShadowException {
		ASTWalker walker = new ASTWalker(this);
		
		walker.walk(node);		
	}
	
	public LinkedList<TACClass> getClasses() {
		return classes;
	}
	
	public Object visit(ASTClassOrInterfaceDeclaration node, Boolean secondVisit) throws ShadowException {
		ClassInterfaceBaseType type = (ClassInterfaceBaseType)node.getType();
		
		// create a new class
		TACClass theClass = new TACClass(node.getImage(), type); 
		
		//
		// TODO: Fix this for multiple fields declared at once: public int x,y;
		//
		
		// go through the fields
		for(Map.Entry<String, Node> field:type.getFields().entrySet()) {
			SimpleNode astNode = (SimpleNode)field.getValue();
			ASTToTAC ast2TAC = new ASTToTAC(astNode);
			
			ast2TAC.convert();	// actually do the conversion
			
			// the entry node of the astNode should always be an allocation
			theClass.addField(astNode.getEntryNode(), astNode.getExitNode());
		}
		
		// generate the init method for the fields
		theClass.generateInitMethod(node);
		
		// go through the methods
		for(Map.Entry<String, List<MethodSignature>> m:type.getMethodMap().entrySet()) {
			if(debug)
				ASTUtils.DEBUG("METHOD: " + m.getKey());
			
			for(MethodSignature ms:m.getValue()) {
				SimpleNode astNode = (SimpleNode)ms.getASTNode();
				
				if( astNode == null )
				{
					System.out.println("Currently using a null AST node for automatically generated default constructors");
					continue;
				}
				
				// there is nothing to build for native methods
				if(ModifierSet.isNative(astNode.getModifiers()))
					continue;
				
				if(debug)
					ASTUtils.DEBUG(astNode, "ROOT NODE");
				
				ASTToTAC ast2TAC = new ASTToTAC(astNode);
				
				ast2TAC.convert();	// actually do the conversion
				
				if(debug)
					ASTUtils.DEBUG(astNode, "ENTRY NODE: " + astNode.getEntryNode());
				
				// add the method to the class
				theClass.addMethod(new TACMethod(ms, astNode.getEntryNode(), astNode.getExitNode()));
			}
		}
		
		// add the class
		classes.add(theClass);
		
		return WalkType.PRE_CHILDREN;
	}


}
