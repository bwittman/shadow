package shadow.tac;

import static shadow.AST.ASTWalker.WalkType.NO_CHILDREN;
import static shadow.AST.ASTWalker.WalkType.POST_CHILDREN;
import static shadow.AST.ASTWalker.WalkType.PRE_CHILDREN;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import shadow.parser.javacc.*;
import shadow.tac.nodes.TACArrayRef;
import shadow.tac.nodes.TACBinary;
import shadow.tac.nodes.TACBranch;
import shadow.tac.nodes.TACCall;
import shadow.tac.nodes.TACCast;
import shadow.tac.nodes.TACClass;
import shadow.tac.nodes.TACFieldRef;
import shadow.tac.nodes.TACLabelRef;
import shadow.tac.nodes.TACLiteral;
import shadow.tac.nodes.TACLoad;
import shadow.tac.nodes.TACNewArray;
import shadow.tac.nodes.TACNewObject;
import shadow.tac.nodes.TACNodeRef;
import shadow.tac.nodes.TACOperand;
import shadow.tac.nodes.TACReference;
import shadow.tac.nodes.TACReturn;
import shadow.tac.nodes.TACSequence;
import shadow.tac.nodes.TACSequenceRef;
import shadow.tac.nodes.TACSingletonRef;
import shadow.tac.nodes.TACStore;
import shadow.tac.nodes.TACUnary;
import shadow.tac.nodes.TACVariableRef;
import shadow.typecheck.type.ArrayType;
import shadow.typecheck.type.ClassInterfaceBaseType;
import shadow.typecheck.type.ClassType;
import shadow.typecheck.type.MethodSignature;
import shadow.typecheck.type.MethodType;
import shadow.typecheck.type.ModifiedType;
import shadow.typecheck.type.Modifiers;
import shadow.typecheck.type.SequenceType;
import shadow.typecheck.type.SimpleModifiedType;
import shadow.typecheck.type.SingletonType;
import shadow.typecheck.type.Type;
import shadow.typecheck.type.UnboundMethodType;

public class TACBuilder implements ShadowParserVisitor
{
	private TACTree tree;
	private TACModule module;
	private TACMethod method;
	private TACOperand prefix;
	private TACVariable identifier;
	private Deque<TACBlock> blocks;
	public TACModule build(SimpleNode node) throws ShadowException
	{
		tree = new TACTree();
		module = null;
		blocks = new LinkedList<TACBlock>();
		walk(node);
		blocks = null;
		identifier = null;
		prefix = null;
		method = null;
		tree = null;
		return module;
	}
	public void walk(Node node) throws ShadowException
	{
		Object type = visit(node, false);
		if (type != NO_CHILDREN)
		{
			tree = tree.next(node.jjtGetNumChildren());
			for (int i = 0; i < node.jjtGetNumChildren(); i++)
				walk(node.jjtGetChild(i));
			if (type == POST_CHILDREN)
				visit(node, true);
		}
		tree = tree.done();
	}

	public Object visit(Node node, Boolean secondVisit) throws ShadowException
	{
		return node.jjtAccept(this, secondVisit);
	}
	@Override
	public Object visit(SimpleNode node, Boolean secondVisit)
			throws ShadowException
	{
		return node.jjtAccept(this, secondVisit);
	}

	@Override
	public Object visit(ASTCompilationUnit node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTImportDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTModifiers node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTTypeDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTViewDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTClassOrInterfaceDeclaration node,
			Boolean secondVisit) throws ShadowException
	{
		ClassInterfaceBaseType type = (ClassInterfaceBaseType)node.getType();
		module = new TACModule(type);

		Set<Node> fields = Collections.newSetFromMap(new IdentityHashMap<Node,
				Boolean>());
		fields.addAll(type.getFields().values());
		for (List<MethodSignature> methodList : type.getMethodMap().values())
			for (MethodSignature method : methodList)
				visitMethod(new TACMethod(method), fields, method.getNode());
		if (!type.getMethodMap().containsKey("construct"))
			visitMethod(new TACMethod("construct", new MethodType(type, new Modifiers())),
					fields, null);

		return NO_CHILDREN;
	}

	@Override
	public Object visit(ASTExtendsList node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTImplementsList node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTVersion node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTEnumDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTEnumBody node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTEnumConstant node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTTypeParameters node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTTypeParameter node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTTypeBound node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTClassOrInterfaceBody node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTClassOrInterfaceBodyDeclaration node,
			Boolean secondVisit) throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTFieldDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTVariableDeclarator node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			Type type = node.getType();
			String name = node.jjtGetChild(0).getImage();
			TACReference ref;
			if (node.isField())
				ref = new TACFieldRef(tree, new TACVariableRef(tree, method.
						getLocal("this")), type, name);
			else
				ref = new TACVariableRef(tree, method.addLocal(type, name));
			if (node.jjtGetNumChildren() == 1)
				new TACStore(tree, ref, getDefaultValue(type));
			else
				new TACStore(tree, ref, tree.appendChild(1));
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTVariableDeclaratorId node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTVariableInitializer node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTArrayInitializer node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTMethodDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit && !(tree.prependAllChildren() instanceof TACReturn))
			new TACReturn(tree);
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTMethodDeclarator node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTFormalParameters node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTFormalParameter node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTConstructDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit && !(tree.prependAllChildren() instanceof TACReturn))
			new TACReturn(tree);
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTDestroyDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit && !(tree.prependAllChildren() instanceof TACReturn))
			new TACReturn(tree);
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTExplicitConstructInvocation node,
			Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTInitializer node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTType node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTReferenceType node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTStaticArrayType node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTFunctionType node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTClassOrInterfaceType node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTClassOrInterfaceTypeSuffix node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTTypeArguments node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTTypeArgument node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTWildcardBounds node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTPrimitiveType node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTResultType node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTResultTypes node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTName node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTUnqualifiedName node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTNameList node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitExpression(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTAssignmentOperator node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTConditionalExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef trueLabel = new TACLabelRef(tree),
					falseLabel = new TACLabelRef(tree),
					doneLabel = new TACLabelRef(tree);
			TACReference var = new TACVariableRef(tree,
					method.addTempLocal(node.getType()));
			new TACBranch(tree, tree.appendChild(0), trueLabel, falseLabel);
			trueLabel.new TACLabel(tree);
			new TACStore(tree, var, tree.appendChild(1));
			new TACBranch(tree, doneLabel);
			falseLabel.new TACLabel(tree);
			new TACStore(tree, var, tree.appendChild(2));
			new TACBranch(tree, doneLabel);
			doneLabel.new TACLabel(tree);
			new TACLoad(tree, var);
		}
		return node.jjtGetNumChildren() == 1 ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTCoalesceExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef nonnullLabel = new TACLabelRef(tree),
					nullLabel = new TACLabelRef(tree),
					doneLabel = new TACLabelRef(tree);
			TACReference var = new TACVariableRef(tree,
					method.addTempLocal(node.getType()));
			TACOperand value = tree.appendChild(0);
			new TACBranch(tree, new TACBinary(tree, value, '!',
					new TACLiteral(tree, "null")), nonnullLabel, nullLabel);
			nonnullLabel.new TACLabel(tree);
			new TACStore(tree, var, value);
			new TACBranch(tree, doneLabel);
			nullLabel.new TACLabel(tree);
			new TACStore(tree, var, tree.appendChild(1));
			new TACBranch(tree, doneLabel);
			doneLabel.new TACLabel(tree);
			new TACLoad(tree, var);
		}
		return node.jjtGetNumChildren() == 1 ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTConditionalOrExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTConditionalExclusiveOrExpression node,
			Boolean secondVisit) throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTConditionalAndExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTBitwiseOrExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTBitwiseExclusiveOrExpression node,
			Boolean secondVisit) throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTBitwiseAndExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTEqualityExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTIsExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTRelationalExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTConcatenationExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTShiftExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTRotateExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTAdditiveExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTMultiplicativeExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitBinaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTUnaryExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitUnaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTUnaryExpressionNotPlusMinus node,
			Boolean secondVisit) throws ShadowException
	{
		if (secondVisit)
			visitUnaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTCastExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			new TACCast(tree, node.getType(), tree.appendChild(1));
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTCheckExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			prefix = tree.appendChild(0);
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTPrimaryExpression node, Boolean secondVisit)
			throws ShadowException
	{
		TACOperand savePrefix = prefix;
		TACVariable saveIdentifier = identifier;
		prefix = null;
		identifier = null;

		tree = tree.next(node.jjtGetNumChildren());
		for (int i = 0; i < node.jjtGetNumChildren(); i++)
			walk(node.jjtGetChild(i));

		prefix = savePrefix;
		identifier = saveIdentifier;
		return NO_CHILDREN;
	}

	@Override
	public Object visit(ASTSequence node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			List<TACOperand> sequence = new ArrayList<TACOperand>();
			for (int i = 0; i < tree.getNumChildren(); i++)
				sequence.add(tree.appendChild(i));
			new TACSequence(tree, sequence);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTMethodCall node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitMethodCall(node);
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTPrimaryPrefix node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			if (node.getImage().equals("("))
				prefix = tree.appendChild(0);
			if (node.getImage().equals("class"))
				prefix = new TACClass(tree, (ClassInterfaceBaseType)node.
						jjtGetChild(0).getType());
			else if (node.getType() instanceof UnboundMethodType)
				identifier = new TACVariable(node.getType(), node.getImage());
			else if (node.isField())
				prefix = new TACFieldRef(tree, new TACVariableRef(tree,
						method.getLocal("this")), node.getType(),
						node.getImage());
			else
			{
				TACVariable var = method.getLocal(node.getImage());
				if (var != null)
					prefix = new TACVariableRef(tree, var);
			}
		}
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTPrimarySuffix node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
		/*if (secondVisit)
		{
			if (node.isField())
				prefix = new TACFieldRef(tree, prefix, node.getType(),
						node.getImage());
			else
				prefix = new TACVariableRef(tree,
						method.getLocal(node.getImage()));
		}
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;*/
	}

	@Override
	public Object visit(ASTQualifiedThis node, Boolean secondVisit)
			throws ShadowException
	{
		// TODO: Make this work
		throw new UnsupportedOperationException();
	}

	@Override
	public Object visit(ASTSubscript node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			List<TACOperand> indicies =
					new ArrayList<TACOperand>(tree.getNumChildren());
			for (int i = 0; i < tree.getNumChildren(); i++)
				indicies.add(tree.appendChild(i));
			prefix = new TACArrayRef(tree, (ArrayType)prefix.getType(),
					prefix, indicies);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTScopeSpecifier node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			prefix = new TACFieldRef(tree, prefix, node.getType(),
					node.getImage());
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTMethod node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			identifier = new TACVariable(node.getType(), node.getImage());
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTProperty node, Boolean secondVisit)
			throws ShadowException
	{
		// TODO: Make this work
		throw new UnsupportedOperationException();
	}

	@Override
	public Object visit(ASTLiteral node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitLiteral(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTIntegerLiteral node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitLiteral(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTBooleanLiteral node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitLiteral(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTNullLiteral node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitLiteral(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTArguments node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			new TACSequence(tree);
		return node.jjtGetNumChildren() != 0 ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTArgumentList node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			List<TACOperand> params = new ArrayList<TACOperand>();
			for (int i = 0; i < tree.getNumChildren(); i++)
			{
				TACOperand param = tree.appendChild(i);
				if (param != null)
					params.add(param);
			}
			new TACSequence(tree, params);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTConstruct node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitConstructor(node);
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTInstance node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			SingletonType type = (SingletonType)node.getType();
			TACLabelRef nonnullLabel = new TACLabelRef(tree),
					nullLabel = new TACLabelRef(tree),
					doneLabel = new TACLabelRef(tree);
			TACReference var = new TACVariableRef(tree,
					method.addTempLocal(type));
			TACReference value = new TACSingletonRef(tree, type);
			new TACBranch(tree, new TACBinary(tree, value, '!',
					new TACLiteral(tree, "null")), nonnullLabel, nullLabel);
			nonnullLabel.new TACLabel(tree);
			new TACStore(tree, var, value);
			new TACBranch(tree, doneLabel);
			nullLabel.new TACLabel(tree);
			TACOperand newValue = new TACNewObject(tree, type);
			new TACCall(tree, new TACMethod("construct",
					new MethodType(type, new Modifiers())), Collections.singleton(newValue));
			new TACStore(tree, value, newValue);
			new TACStore(tree, var, newValue);
			new TACBranch(tree, doneLabel);
			doneLabel.new TACLabel(tree);
			new TACLoad(tree, var);
		}
		return POST_CHILDREN;
	}

	/*
	@Override
	public Object visit(ASTArrayDimsAndInits node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			List<ModifiedType> types = new ArrayList<ModifiedType>();
			List<TACOperand> seq = new ArrayList<TACOperand>();
			for (int i = 0; i < tree.getNumChildren(); i++)
			{
				TACOperand child = tree.appendChild(i);
				types.add(new SimpleModifiedType(child.getType()));
				seq.add(child);
			}
			new TACSequence(tree, seq);
		}
		return POST_CHILDREN;
	}
	
	*/

	@Override
	public Object visit(ASTStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTAssertStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTBlock node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			method.exitScope();
		else
			method.enterScope();
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTBlockStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTLocalVariableDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTEmptyStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTStatementExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			if (node.getImage().equals("="))
			{
				int index = tree.getNumChildren() - 1;
				TACOperand current = tree.appendChild(index);
				while (--index >= 0)
				{
					new TACStore(tree, new TACSequenceRef(tree, tree.
							appendChildRemoveSequence(index)), current);
					current = (TACOperand)tree.getLast();
				}
			}
			else
				visitExpression(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTSwitchStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTSwitchLabel node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTIfStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef trueLabel = new TACLabelRef(tree),
					falseLabel = new TACLabelRef(tree),
					endLabel = new TACLabelRef(tree);
			new TACBranch(tree, tree.appendChild(0), trueLabel, falseLabel);
			trueLabel.new TACLabel(tree);
			tree.appendChild(1);
			new TACBranch(tree, endLabel);
			falseLabel.new TACLabel(tree);
			tree.appendChild(2);
			new TACBranch(tree, endLabel);
			endLabel.new TACLabel(tree);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTWhileStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitLoop(0, 1, false);
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTDoStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitLoop(1, 0, true);
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTForeachStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTForStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			int condition = 0;
			if (node.jjtGetChild(condition) instanceof ASTForInit)
				tree.appendChild(condition++);
			int body = condition + 1;
			if (node.jjtGetChild(body) instanceof ASTForUpdate)
				tree.getChild(++body).append(tree.getChild(body - 1));
			visitLoop(condition, body, false);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTForInit node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTStatementExpressionList node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTForUpdate node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTBreakStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTContinueStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTReturnStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			new TACReturn(tree, (SequenceType)node.getType(),
					tree.appendChild(0));
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTThrowStatement node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTTryStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef cleanup = blocks.pop().getCleanup();
			tree.append(cleanup);
			TACLabelRef endLabel = new TACLabelRef(tree);
			int index = 0;
			tree.appendChild(index++);
			new TACBranch(tree, endLabel);
			for (int i = 0; i < node.getCatches(); i++)
			{
				index++;
				index++;
				new TACBranch(tree, endLabel);
			}
			if (node.hasRecover())
			{
				cleanup.new TACLabel(tree);
				tree.appendChild(index++);
				new TACBranch(tree, endLabel);
			}
			endLabel.new TACLabel(tree);
			if (node.hasFinally())
			{
				index++;
			}
		}
		else
		{
			if (node.getCatches() != 0)
				throw new UnsupportedOperationException();
			if (node.hasRecover())
				blocks.push(new TACBlock(new TACLabelRef()));
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTRightRotate node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTRightShift node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	private void visitMethod(TACMethod methodRef, Set<Node> fieldNodes,
			Node methodNode) throws ShadowException
	{
		if (methodRef.isGetClass())
			return;
		method = methodRef;
		if (!methodRef.isNative())
		{
			tree = tree.next();
			if (methodRef.isConstructor())
				for (Node field : fieldNodes)
					walk(field);
			if (methodNode == null)
				new TACReturn(tree);
			else
				walk(methodNode);
			tree = tree.done();
			methodRef.append(tree.current());
		}
		module.addMethod(methodRef);
		System.out.println(methodRef);
		method = null;
	}

	private void visitLiteral(SimpleNode node)
	{
		new TACLiteral(tree, node.getImage());
	}

	private void visitUnaryOperation(SimpleNode node)
	{
		new TACUnary(tree, node.getImage().charAt(0), tree.appendChild(0));
	}

	private void visitBinaryOperation(SimpleNode node)
	{
		int child = 0;
		TACOperand current = null;
		while (current == null)
			current = tree.appendChild(child++);
		String operations = node.getImage();
		for (int i = 0; i < operations.length(); i++)
		{
			char operation = operations.charAt(i);
			TACOperand next = null;
			while (next == null)
				next = tree.appendChild(child++);
			current = new TACBinary(tree, current, operation, next);
		}
	}

	private void visitExpression(SimpleNode node)
	{
		TACOperand value = tree.appendChild(2);
		TACReference var = (TACReference)tree.appendChild(0);
		char operation = node.jjtGetChild(1).getImage().charAt(0);
		if (operation != '=')
			value = new TACBinary(tree, var, operation, value);
		new TACStore(tree, var, value);
	}

	private void visitLoop(int condition, int body, boolean atLeastOnce)
	{
		TACLabelRef bodyLabel = new TACLabelRef(tree),
				conditionLabel = new TACLabelRef(tree),
				endLabel = new TACLabelRef(tree);
		new TACBranch(tree, atLeastOnce ? bodyLabel : conditionLabel);
		bodyLabel.new TACLabel(tree);
		tree.appendChild(body);
		new TACBranch(tree, conditionLabel);
		conditionLabel.new TACLabel(tree);
		new TACBranch(tree, tree.appendChild(condition), bodyLabel, endLabel);
		endLabel.new TACLabel(tree);
	}

	private void visitMethodCall(ASTMethodCall node)
	{
		MethodType type = (MethodType) node.getType();
		TACMethod methodRef = new TACMethod(identifier.getName(), type);
		List<TACOperand> params = new ArrayList<TACOperand>();
		params.add(prefix != null ? prefix :
				new TACVariableRef(tree, method.getLocal("this")));
		for (int i = 0; i < tree.getNumChildren(); i++)
			params.add(tree.appendChild(i));
		prefix = new TACCall(tree, methodRef, params);
	}

	private void visitConstructor(SimpleNode node)
	{
		MethodType methodType = (MethodType)node.getType();
		ClassType type = (ClassType)node.jjtGetChild(0).getType();
		TACOperand object = new TACNewObject(tree, type);
		if (!type.getMethodMap().containsKey("construct"))
			methodType = new MethodType(type, new Modifiers());
//		SequenceType paramTypes = (SequenceType)node.jjtGetChild(1).getType();
//		List<MethodSignature> ctors = type.getMethodMap().get("construct");
//		if (ctors == null) // Default construct
//			methodType = new MethodType(type, 0);
//		else
//			for (MethodSignature sig : ctors)
//				if (sig.canAccept(paramTypes))
//					methodType = sig.getMethodType();
		TACMethod methodRef = new TACMethod("construct", methodType);
		TACSequence sequence = tree.appendChildRemoveSequence(1);
		List<TACOperand> params =
				new ArrayList<TACOperand>(sequence.size() + 1);
		params.add(object);
		for (int i = 0; i < sequence.size(); i++)
			params.add(sequence.get(i));
		new TACCall(tree, methodRef, params);
		new TACNodeRef(tree, object);
	}

	private TACNodeRef visitArrayAllocation(ArrayType type,
			List<TACOperand> sizes)
	{
		TACNewArray alloc = new TACNewArray(tree, type, sizes.subList(0,
				type.getDimensions()));
		sizes = sizes.subList(type.getDimensions(), sizes.size());
		if (!sizes.isEmpty())
		{
			TACReference index = new TACVariableRef(tree, method.addTempLocal(
					Type.LONG));
			new TACStore(tree, index, new TACLiteral(tree, "0l"));
			TACLabelRef bodyLabel = new TACLabelRef(tree),
					condLabel = new TACLabelRef(tree),
					endLabel = new TACLabelRef(tree);
			new TACBranch(tree, condLabel);
			bodyLabel.new TACLabel(tree);
			new TACStore(tree, new TACArrayRef(tree, type, alloc, index),
					visitArrayAllocation((ArrayType)type.getBaseType(), sizes));
			new TACStore(tree, index, new TACBinary(tree, index, '+',
					new TACLiteral(tree, "1l")));
			new TACBranch(tree, condLabel);
			condLabel.new TACLabel(tree);
			new TACBranch(tree, new TACBinary(tree, index, '!', alloc.
					getTotalSize()), bodyLabel, endLabel);
			endLabel.new TACLabel(tree);
		}
		return new TACNodeRef(tree, alloc);
	}

//	private TACReference visitArrayAllocation(TACData tac, ArrayType type, List<TACNode> sizes, int sizeIndex)
//	{
//		int startIndex = sizeIndex;
//		TACNode size = sizes.get(sizeIndex++);
//		for (int i = 1; i < type.getDimensions(); i++)
//			tac.append(size = new TACBinary(size, TACBinary.Operator.MULTIPLY, sizes.get(sizeIndex++)));
//		TACAllocation alloc = new TACAllocation(type, size, sizes, startIndex);
//		tac.append(alloc);
//		if (sizeIndex < sizes.size())
//		{
//			TACOldVariable index = new TACOldVariable(Type.INT);
//			tac.append(new TACAllocation(index));
//			tac.append(new TACLiteral(Type.INT, 0));
//			tac.append(new TACAssign(index, tac.getNode()));
//			TACComparison condition = new TACComparison(index, TACComparison.Operator.NOT_EQUAL, size);
//			TACLabel bodyLabel = new TACLabel("body"),
//					conditionLabel = new TACLabel("condition"),
//					endLabel = new TACLabel("end");
//			tac.append(new TACBranch(conditionLabel));
//			tac.append(bodyLabel);
//			TACReference value = visitArrayAllocation(tac, (ArrayType)type.getBaseType(), sizes, sizeIndex);
//			tac.append(new TACIndexed(type.getBaseType(), alloc, new TACReference(index)));
//			tac.append(new TACAssign(tac.getNode(), new TACReference(value)));
//			tac.append(new TACAssign(
//					tac.append(new TACReference(index)),
//					tac.append(new TACBinary(
//							tac.append(new TACReference(index)),
//							TACBinary.Operator.ADD,
//							tac.append(new TACLiteral(Type.INT, 1))))));
//			tac.append(new TACBranch(conditionLabel));
//			tac.append(conditionLabel);
//			tac.append(condition);
//			tac.append(new TACBranch(condition, bodyLabel, endLabel));
//			tac.append(endLabel);
//		}
//		return new TACReference(alloc);
//	}

	private TACLiteral getDefaultValue(Type type)
	{
		if (type.equals(Type.BOOLEAN))
			return new TACLiteral(tree, "false");
		if (type.equals(Type.UBYTE))
			return new TACLiteral(tree, "0uy");
		if (type.equals(Type.BYTE))
			return new TACLiteral(tree, "0y");
		if (type.equals(Type.USHORT))
			return new TACLiteral(tree, "0us");
		if (type.equals(Type.SHORT))
			return new TACLiteral(tree, "0s");
		if (type.equals(Type.UINT))
			return new TACLiteral(tree, "0ui");
		if (type.equals(Type.INT))
			return new TACLiteral(tree, "0i");
		if (type.equals(Type.ULONG))
			return new TACLiteral(tree, "0ul");
		if (type.equals(Type.LONG))
			return new TACLiteral(tree, "0l");
		return new TACLiteral(tree, "null");
	}

	@Override
	public Object visit(ASTSequenceAssignment node, Boolean data)
			throws ShadowException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object visit(ASTLocalDeclaration node, Boolean data)
			throws ShadowException {
		// TODO Auto-generated method stub
		return null;
	}
	@Override
	public Object visit(ASTLocalMethodDeclaration node, Boolean data)
			throws ShadowException {
		// TODO Auto-generated method stub
		return null;
	}
	@Override
	public Object visit(ASTQualifiedSuper node, Boolean data)
			throws ShadowException {
		// TODO Auto-generated method stub
		return null;
	}
	@Override
	public Object visit(ASTBrackets node, Boolean data) throws ShadowException {
		// TODO Auto-generated method stub
		return null;
	}
}
