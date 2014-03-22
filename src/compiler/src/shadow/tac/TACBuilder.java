package shadow.tac;

import static shadow.AST.ASTWalker.WalkType.NO_CHILDREN;
import static shadow.AST.ASTWalker.WalkType.POST_CHILDREN;
import static shadow.AST.ASTWalker.WalkType.PRE_CHILDREN;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import shadow.parser.javacc.*;
import shadow.tac.nodes.TACArrayRef;
import shadow.tac.nodes.TACBinary;
import shadow.tac.nodes.TACBlock;
import shadow.tac.nodes.TACBranch;
import shadow.tac.nodes.TACCall;
import shadow.tac.nodes.TACCast;
import shadow.tac.nodes.TACCatch;
import shadow.tac.nodes.TACClass;
import shadow.tac.nodes.TACConstantRef;
import shadow.tac.nodes.TACDestination;
import shadow.tac.nodes.TACFieldRef;
import shadow.tac.nodes.TACInit;
import shadow.tac.nodes.TACLabelRef;
import shadow.tac.nodes.TACLabelRef.TACLabel;
import shadow.tac.nodes.TACLandingpad;
import shadow.tac.nodes.TACLength;
import shadow.tac.nodes.TACLiteral;
import shadow.tac.nodes.TACLoad;
import shadow.tac.nodes.TACMethodRef;
import shadow.tac.nodes.TACNewArray;
import shadow.tac.nodes.TACNewObject;
import shadow.tac.nodes.TACNode;
import shadow.tac.nodes.TACNodeRef;
import shadow.tac.nodes.TACNot;
import shadow.tac.nodes.TACOperand;
import shadow.tac.nodes.TACPhiRef.TACPhi;
import shadow.tac.nodes.TACPropertyRef;
import shadow.tac.nodes.TACReference;
import shadow.tac.nodes.TACResume;
import shadow.tac.nodes.TACReturn;
import shadow.tac.nodes.TACSame;
import shadow.tac.nodes.TACSequence;
import shadow.tac.nodes.TACSequenceRef;
import shadow.tac.nodes.TACSingletonRef;
import shadow.tac.nodes.TACStore;
import shadow.tac.nodes.TACThrow;
import shadow.tac.nodes.TACTypeId;
import shadow.tac.nodes.TACUnary;
import shadow.tac.nodes.TACUnwind;
import shadow.tac.nodes.TACVariableRef;
import shadow.typecheck.type.ArrayType;
import shadow.typecheck.type.ClassType;
import shadow.typecheck.type.ExceptionType;
import shadow.typecheck.type.InterfaceType;
import shadow.typecheck.type.MethodSignature;
import shadow.typecheck.type.MethodType;
import shadow.typecheck.type.ModifiedType;
import shadow.typecheck.type.Modifiers;
import shadow.typecheck.type.PropertyType;
import shadow.typecheck.type.SequenceType;
import shadow.typecheck.type.SimpleModifiedType;
import shadow.typecheck.type.SingletonType;
import shadow.typecheck.type.Type;
import shadow.typecheck.type.UnboundMethodType;

public class TACBuilder implements ShadowParserVisitor
{
	private Collection<TACModule> modules;
	private TACTree tree;
	private TACModule module;
	private TACMethod method;
	private TACOperand prefix;
	private boolean explicitSuper, implicitCreate;
	private TACVariable identifier;
	private TACBlock block;
	public Collection<TACModule> build(Node node) throws ShadowException
	{
		modules = null;
		tree = new TACTree();
		module = null;
		method = null;
		prefix = null;
		explicitSuper = false;
		implicitCreate = false;
		identifier = null;
		block = null;
		walk(node);
		return modules;
	}
	public void walk(Node node) throws ShadowException
	{
		tree = tree.next(node.jjtGetNumChildren());
		Object type = visit(node, false);
		if (type != NO_CHILDREN)
		{
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
	public Object visit(ASTViewDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTClassOrInterfaceDeclaration node,
			Boolean secondVisit) throws ShadowException
	{
		Type type = node.getType();
		module = new TACModule(type);

		for (Node constant : type.getFields().values())
			if (constant.getModifiers().isConstant())
				visitConstant(new TACConstant(type,
						constant.getImage()), constant);
		for (List<MethodSignature> methods : type.getMethodMap().values())
			for (MethodSignature method : methods)
				if (method.isCreate() || method.getModifiers().isPrivate())
					visitMethod(method);
		if (module.isClass())
			for (InterfaceType interfaceType : type.getAllInterfaces())
				for (MethodSignature method : interfaceType.orderMethods(module.
						getClassType()))
					if (method.isWrapper())
						visitMethod(method);
		for (MethodSignature method : type.orderMethods())
			visitMethod(method);

		List<TACModule> saveModules = new ArrayList<TACModule>();
		saveModules.add(module);
		TACTree saveTree = tree;

		Node body = node.jjtGetChild(node.jjtGetNumChildren() - 1);
		for (int i = 0; i < body.jjtGetNumChildren(); i++)
		{
			SimpleNode child = (SimpleNode)body.jjtGetChild(i);
			if (child.jjtGetNumChildren() > 1 && child.jjtGetChild(1) instanceof
					ASTClassOrInterfaceDeclaration)
				saveModules.addAll(build(child));
		}

		modules = saveModules;
		tree = saveTree;
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
			String name = node.getImage();
			TACReference ref;
			if (node.isField())
				ref = new TACFieldRef(tree,
						new TACVariableRef(tree, method.getThis()), name);
			else
				ref = new TACVariableRef(tree, method.addLocal(node, name));
			if (node.jjtGetNumChildren() == 0)
				new TACStore(tree, ref, getDefaultValue(node));
			else
				new TACStore(tree, ref, tree.appendChild(0));
		}
		return POST_CHILDREN;
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
		if (secondVisit)
		{
			/*if (node.jjtGetNumChildren() == 0)
			{
				TACFieldRef field = new TACFieldRef(tree, new TACVariableRef(
						tree, method.getThis()), node.getImage());
				if (node.getModifiers().isGet())
					new TACReturn(tree, method.getReturnTypes(),
							new TACLoad(tree, field));
				else if (node.getModifiers().isSet())
				{
					TACVariable value = null;
					for (TACVariable parameter : method.getParameters())
						value = parameter;
					new TACStore(tree, field, new TACVariableRef(tree, value));
					new TACReturn(tree, method.getReturnTypes());
				}
			}
			else */
			TACNode last = tree.appendAllChildren();
			if (last instanceof TACLabel &&
					last.getPrevious() instanceof TACReturn)
				last.remove();
			else
			{
				TACMethodRef methodRef = method.getMethod();
				if (methodRef.isVoid())
					new TACReturn(tree, methodRef.getReturnTypes());
				else if (methodRef.isSingle())
					new TACReturn(tree, methodRef.getReturnTypes(),
							getDefaultValue(methodRef.getSingleReturnType()));
				else
				{
					List<TACOperand> seq = new ArrayList<TACOperand>();
					for (ModifiedType type : methodRef.getReturnTypes())
						seq.add(getDefaultValue(type));
					new TACReturn(tree, methodRef.getSequenceReturnTypes(),
							new TACSequence(tree, seq));
				}
			}
		}
		return POST_CHILDREN;
	}
	@Override
	public Object visit(ASTMethodDeclarator node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTCreateDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACNode last = tree.appendAllChildren();
			if (last instanceof TACLabel &&
					last.getPrevious() instanceof TACReturn)
				last.remove();
			else
				new TACReturn(tree, method.getMethod().getReturnTypes(),
						new TACVariableRef(tree, method.getThis()));
		}
		return POST_CHILDREN;
	}
	@Override
	public Object visit(ASTCreateDeclarator node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTDestroyDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACNode last = tree.appendAllChildren();
			if (last instanceof TACLabel &&
					last.getPrevious() instanceof TACReturn)
				last.remove();
			else
				new TACReturn(tree, method.getMethod().getReturnTypes());
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTExplicitCreateInvocation node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			implicitCreate = false;
			ClassType thisType = (ClassType)method.getMethod().getPrefixType();
			List<TACOperand> params = new ArrayList<TACOperand>();
			params.add(new TACVariableRef(tree, method.getThis()));
			for (int i = 0; i < tree.getNumChildren(); i++)
			{
				TACOperand param = tree.appendChild(i); 
				params.add(param);
				//type.addParameter(param);
			}
			new TACCall(tree, block, new TACMethodRef(tree, node.getMethodSignature()),
					params);
			if (node.getImage().equals("super"))
				new TACInit(tree, thisType);
		}
		return POST_CHILDREN;
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
		if (secondVisit)
			new TACVariableRef(tree,
					method.addLocal(node, node.getImage()));
		return POST_CHILDREN;
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
					method.addTempLocal(node));
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
					method.addTempLocal(node));
			TACOperand value = tree.appendChild(0);
			new TACBranch(tree, new TACSame(tree, value, new TACLiteral(tree,
					"null")), nullLabel, nonnullLabel);
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
		{
			TACLabelRef doneLabel = new TACLabelRef(tree);
			TACReference var = new TACVariableRef(tree,
					method.addTempLocal(node));
			TACOperand value = tree.appendChild(0);
			new TACStore(tree, var, value);
			for (int i = 1; i < tree.getNumChildren(); i++)
			{
				TACLabelRef nextLabel = new TACLabelRef(tree);
				new TACBranch(tree, value, doneLabel, nextLabel);
				nextLabel.new TACLabel(tree);
				new TACStore(tree, var, value = tree.appendChild(i));
			}
			new TACBranch(tree, doneLabel);
			doneLabel.new TACLabel(tree);
			new TACLoad(tree, var);
		}
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTConditionalExclusiveOrExpression node,
			Boolean secondVisit) throws ShadowException
	{
		if (secondVisit)
			visitBooleanOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTConditionalAndExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef doneLabel = new TACLabelRef(tree);
			TACReference var = new TACVariableRef(tree,
					method.addTempLocal(node));
			TACOperand value = tree.appendChild(0);
			new TACStore(tree, var, value);
			for (int i = 1; i < tree.getNumChildren(); i++)
			{
				TACLabelRef nextLabel = new TACLabelRef(tree);
				new TACBranch(tree, value, nextLabel, doneLabel);
				nextLabel.new TACLabel(tree);
				new TACStore(tree, var, value = tree.appendChild(i));
			}
			new TACBranch(tree, doneLabel);
			doneLabel.new TACLabel(tree);
			new TACLoad(tree, var);
		}
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
		{
			int index = 0;
			String image = node.getImage();
			TACOperand value = tree.appendChild(index);
			while (index < image.length())
			{
				char c = image.charAt(index);
				if (c == '=' || c == '!')
				{
					TACOperand other = tree.appendChild(++index);
					if (node.jjtGetChild(index - 1).getType().isPrimitive() &&
							node.jjtGetChild(index).getType().isPrimitive())
					{
						value = new TACSame(tree, value, other);
					}
					else
					{
						TACLabelRef nullLabel = new TACLabelRef(tree),
								nonnullLabel = new TACLabelRef(tree),
								doneLabel = new TACLabelRef(tree);
						TACVariableRef var = new TACVariableRef(tree,
								method.addTempLocal(node));
						TACLiteral nullLiteral = new TACLiteral(tree, "null");
						new TACBranch(tree, new TACSame(tree, value,
								nullLiteral), nullLabel, nonnullLabel);
						nullLabel.new TACLabel(tree);
						new TACStore(tree, var, new TACSame(tree, other,
								nullLiteral));
						new TACBranch(tree, doneLabel);
						nonnullLabel.new TACLabel(tree);
						Type valueType = value.getType();
						if( valueType instanceof PropertyType )
							valueType = ((PropertyType)valueType).getGetType().getType();
						MethodSignature signature = valueType.getMatchingMethod("equal", new SequenceType(other));												
						new TACStore(tree, var, new TACCall(tree, block,
								new TACMethodRef(tree, value, signature),
								value, other));
						new TACBranch(tree, doneLabel);
						doneLabel.new TACLabel(tree);
						value = new TACLoad(tree, var);						
					}
				}
				else
					value = new TACSame(tree, value, tree.appendChild(++index));
				if (c == '!' || c == 'n')
					value = new TACNot(tree, value);
			}
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTIsExpression node, Boolean secondVisit)
			throws ShadowException
	{
		
		if (secondVisit)
		{
			throw new UnsupportedOperationException();
			//visitBinaryOperation(node);
		}
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;		
		//how is an "is" expression done?  repeated comparison of typeid values?		
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
		{
			TACOperand last = null;			
			for (int i = 0; i < tree.getNumChildren(); i++)
			{
				TACOperand operand = tree.appendChild(i);
				Type type = operand.getType();
				if (!type.isPrimitive() && !(type instanceof ArrayType))
				{ // TODO: actually check nullable
					TACLabelRef nullLabel = new TACLabelRef(tree),
							nonnullLabel = new TACLabelRef(tree),
							doneLabel = new TACLabelRef(tree);
					TACReference var = new TACVariableRef(tree,
							method.addTempLocal(node));
					new TACBranch(tree, new TACSame(tree, operand,
							new TACLiteral(tree, "null")), nullLabel,
							nonnullLabel);
					nullLabel.new TACLabel(tree);
					new TACStore(tree, var, new TACLiteral(tree, "\"null\""));
					new TACBranch(tree, doneLabel);
					nonnullLabel.new TACLabel(tree);
					new TACStore(tree, var, new TACCall(tree, block,
							new TACMethodRef(tree, operand,
									type.getMatchingMethod("toString", new SequenceType())),
							Collections.singletonList(operand)));
					new TACBranch(tree, doneLabel);
					doneLabel.new TACLabel(tree);
					operand = new TACLoad(tree, var);
				}
				else
				{
					operand = new TACCall(tree, block,
							new TACMethodRef(tree, operand,
									type.getMatchingMethod("toString", new SequenceType())),
							Collections.singletonList(operand));
				}
				last = i == 0 ? operand : new TACCall(tree, block,
						new TACMethodRef(tree,
								Type.STRING.getMethods("concatenate").get(0)),
						Arrays.asList(last, operand));
			}
		}
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

	/*
	@Override
	public Object visit(ASTUnaryToString node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACOperand operand = tree.appendChild(0);
			TACLabelRef nullLabel = new TACLabelRef(tree),
					nonnullLabel = new TACLabelRef(tree),
					doneLabel = new TACLabelRef(tree);
			TACReference var = new TACVariableRef(tree,
					method.addTempLocal(node));
			new TACBranch(tree, new TACSame(tree, operand, new TACLiteral(tree,
					"null")), nullLabel, nonnullLabel);
			nullLabel.new TACLabel(tree);
			new TACStore(tree, var, new TACLiteral(tree, "\"null\""));
			new TACBranch(tree, doneLabel);
			nonnullLabel.new TACLabel(tree);
			new TACStore(tree, var, new TACCall(tree, block,
					new TACMethodRef(tree, operand,
							Type.OBJECT.getMethods("toString").get(0)),
					Collections.singletonList(operand)));
			new TACBranch(tree, doneLabel);
			doneLabel.new TACLabel(tree);
			new TACLoad(tree, var);
		}
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}
	*/

	@Override
	public Object visit(ASTUnaryExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACOperand operand = tree.appendChild(0);
			String op = node.getImage(); 
			if( op.equals("#")) //string is special because of nulls
			{	
				TACLabelRef nullLabel = new TACLabelRef(tree),
						nonnullLabel = new TACLabelRef(tree),
						doneLabel = new TACLabelRef(tree);
				TACReference var = new TACVariableRef(tree,
						method.addTempLocal(node));
				new TACBranch(tree, new TACSame(tree, operand, new TACLiteral(tree,
						"null")), nullLabel, nonnullLabel);
				nullLabel.new TACLabel(tree);
				new TACStore(tree, var, new TACLiteral(tree, "\"null\""));
				new TACBranch(tree, doneLabel);
				nonnullLabel.new TACLabel(tree);
				new TACStore(tree, var, new TACCall(tree, block,
						new TACMethodRef(tree, operand, node.getOperations().get(0)),
						Collections.singletonList(operand)));
				new TACBranch(tree, doneLabel);
				doneLabel.new TACLabel(tree);
				new TACLoad(tree, var);
			}
			else
			{
				Type type = operand.getType();
				if( type instanceof PropertyType )
					type = ((PropertyType)type).getGetType().getType();
					
				if( op.equals("!") )
					new TACUnary(tree, "!", operand);
				else
				{
					MethodSignature signature = node.getOperations().get(0); 
					if( type.isPrimitive() && signature.getModifiers().isNative() )
						new TACUnary(tree, signature, op, operand);				
					else
					{
						TACVariableRef var = new TACVariableRef(tree,
								method.addTempLocal(node));
						new TACStore(tree, var, new TACCall(tree, block, new TACMethodRef(tree, operand, node.getOperations().get(0)), operand));		
						new TACLoad(tree, var);
					}
				}
			}
		}
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	/*
	@Override
	public Object visit(ASTUnaryExpressionNotPlusMinus node,
			Boolean secondVisit) throws ShadowException
	{
		if (secondVisit)
			visitUnaryOperation(node);
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}
	*/

	@Override
	public Object visit(ASTInlineMethodDefinition node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			throw new UnsupportedOperationException();
		return node.jjtGetNumChildren() == 1 ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTCastExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			new TACCast(tree, node, tree.appendChild(1));
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTCheckExpression node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef continueLabel = new TACLabelRef(tree);
			TACOperand operand = tree.appendChild(0);
			new TACBranch(tree, new TACSame(tree, operand, new TACLiteral(tree,
					"null")), block.getRecover(), continueLabel);
			continueLabel.new TACLabel(tree);
			prefix = new TACNodeRef(tree, operand);
		}
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
		tree = tree.done();

		prefix = savePrefix;
		identifier = saveIdentifier;
		return NO_CHILDREN;
	}

	@Override
	public Object visit(ASTPrimaryPrefix node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			identifier = new TACVariable(node, node.getImage());
			if (node.isImageNull())
				prefix = tree.appendChild(0);
			else
			{
				String name = node.getImage();
				explicitSuper = name.equals("super");
				if (!(/*explicitSuper ||*/ node.getModifiers().isTypeName() ||
						node.getType() instanceof UnboundMethodType))
				{
					TACVariable local;
					if( explicitSuper )
						local = method.getLocal("this");
					else
						local = method.getLocal(name);
					if (local != null)
						prefix = new TACVariableRef(tree, local);
					else
					{
						TACReference thisRef =
								new TACVariableRef(tree, method.getThis());
						while (!thisRef.getType().containsField(name))
							thisRef = new TACFieldRef(tree, thisRef,
									new SimpleModifiedType(thisRef.getType().
											getOuter()), "this");
						if (node.getModifiers().isConstant())
							prefix = new TACConstantRef(tree, thisRef.getType(),
									name);
						else
							prefix = new TACFieldRef(tree, thisRef, name);
					}
				}				
			}
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTAllocation node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTPrimarySuffix node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTQualifiedKeyword node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			if (node.getImage().equals("class"))
				new TACClass(tree, identifier.getType(), method);
			else // TODO: Make this work
				throw new UnsupportedOperationException();
		}
		return POST_CHILDREN;
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
			prefix = new TACArrayRef(tree, prefix, indicies);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTScopeSpecifier node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			prefix = new TACFieldRef(tree, prefix, node.getImage());
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTMethod node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			identifier = new TACVariable(node, node.getImage());
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTProperty node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			if (prefix != null && prefix.getType() instanceof ArrayType &&
					node.getImage().equals("length"))
			{
				ArrayType arrayType = (ArrayType)prefix.getType();
				TACOperand length = new TACLength(tree, prefix, 0);
				for (int i = 1; i < arrayType.getDimensions(); i++)
					length = new TACBinary(tree, length, Type.INT.getMatchingMethod("multiply", new SequenceType(Type.INT)), '*', new TACLength(tree, prefix, i), false);
				prefix = length;
			}
			else
				prefix = new TACPropertyRef(tree, block, prefix,
						(PropertyType)node.getType(), node.getImage());
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTMethodCall node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			if (prefix == null)
				prefix = new TACVariableRef(tree, method.getThis());
			TACMethodRef methodRef = new TACMethodRef(tree,
					explicitSuper ? null : prefix,
					((MethodType)node.getType()).getTypeWithoutTypeArguments(),
					identifier.getName());
			List<TACOperand> params = new ArrayList<TACOperand>();
			params.add(prefix);
			for (int i = 0; i < tree.getNumChildren(); i++)
				if (node.jjtGetChild(i) instanceof ASTTypeArguments)
					for (ModifiedType type :
							(SequenceType)node.jjtGetChild(i).getType())
						params.add(new TACClass(tree, type.getType(), method));
				else
					params.add(tree.appendChild(i));
			prefix = new TACCall(tree, block, methodRef, params);
			explicitSuper = false;
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTLiteral node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			prefix = new TACLiteral(tree, node.getImage());
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTIntegerLiteral node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			prefix = new TACLiteral(tree, node.getImage());
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTBooleanLiteral node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			prefix = new TACLiteral(tree, node.getImage());
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	@Override
	public Object visit(ASTNullLiteral node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			prefix = new TACLiteral(tree, node.getImage());
		return node.isImageNull() ? PRE_CHILDREN : POST_CHILDREN;
	}

	/*
	@Override
	public Object visit(ASTArguments node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			new TACSequence(tree);
		return node.jjtGetNumChildren() != 0 ? PRE_CHILDREN : POST_CHILDREN;
	}
	*/

	@Override
	public Object visit(ASTArguments node, Boolean secondVisit) //used to be ASTArgumentList
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
	public Object visit(ASTArrayCreate node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			List<TACOperand> sizes = new ArrayList<TACOperand>(
					tree.getNumChildren());
			for (int i = 0; i < tree.getNumChildren(); i++)
				sizes.add(tree.appendChild(i));
			prefix = visitArrayAllocation((ArrayType)node.getType(), sizes);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTCreate node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACMethodRef methodRef = new TACMethodRef(tree,
					(MethodType)node.getType(), node.getImage());
			TACOperand object = new TACNewObject(tree,
					(ClassType)methodRef.getPrefixType());
			List<TACOperand> params = new ArrayList<TACOperand>();
			params.add(object);
			if (methodRef.getPrefixType().hasOuter())
			{
				Type outerType = methodRef.getPrefixType().getOuter();
				TACVariable thisRef = method.getThis();
				if (thisRef.getType().equals(outerType))
					params.add(new TACVariableRef(tree, thisRef));
				else
					throw new UnsupportedOperationException();
			}
			for (int i = 0; i < tree.getNumChildren(); i++)
				if (node.jjtGetChild(i) instanceof ASTTypeArguments)
					for (ModifiedType type : (SequenceType)
							node.jjtGetChild(i).getType())
						params.add(new TACClass(tree, type.getType(), method));
				else
					params.add(tree.appendChild(i));
			new TACCall(tree, block, methodRef, params);
			prefix = new TACNodeRef(tree, object);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTInstance node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			SingletonType type = (SingletonType)node.getType();
			TACLabelRef initLabel = new TACLabelRef(tree),
					doneLabel = new TACLabelRef(tree);
			TACReference instance = new TACSingletonRef(tree, type);
			new TACBranch(tree, new TACSame(tree, instance, new TACLiteral(tree,
					"null")), initLabel, doneLabel);
			initLabel.new TACLabel(tree);
			new TACStore(tree, instance, new TACCall(tree, block,
					new TACMethodRef(tree, type.getMethods("create").get(0)),
					Collections.singletonList(new TACNewObject(tree, type))));
			new TACBranch(tree, doneLabel);
			doneLabel.new TACLabel(tree);
			prefix = new TACLoad(tree, instance);
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
	public Object visit(ASTCreateBlock node, Boolean secondVisit)
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
	public Object visit(ASTLocalDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTLocalMethodDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		throw new UnsupportedOperationException();
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
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTSequenceAssignment node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			ASTRightSide rightSide = (ASTRightSide) node.jjtGetChild(1);
			ASTSequenceLeftSide leftSide = (ASTSequenceLeftSide) node.jjtGetChild(0);
			//create splat
			if( !(rightSide.getType() instanceof SequenceType)  )
			{				
				TACReference temporary = new TACVariableRef(tree,
						method.addTempLocal(rightSide));	
				new TACStore(tree, temporary, tree.appendChild(1));  //is that right? child 0 didn't work

				
				List<TACOperand> sequence =
						new ArrayList<TACOperand>(leftSide.getType().size());
				
				for (int index = 0; index < leftSide.getType().size(); index++ )				
						sequence.add(new TACLoad(tree, temporary));
				
				new TACStore(tree, new TACSequenceRef(tree,
						tree.appendChildRemoveSequence(0)), new TACSequence(tree, sequence) );
			}
			else
			{
				new TACStore(tree, new TACSequenceRef(tree,
						tree.appendChildRemoveSequence(0)), tree.appendChild(1));
			}
		}
		
		
		return POST_CHILDREN;
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
			visitLoop(0, 1, 0, false);
		else
			visitLoop();
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTDoStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
			visitLoop(1, 0, 1, true);
		else
			visitLoop();
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
			int update = condition;
			if (node.jjtGetChild(update + 1) instanceof ASTForUpdate)
				update++;
			int body = update + 1;
			visitLoop(condition, body, update, false);
		}
		else
			visitLoop();
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
		if (secondVisit)
		{
			TACLabelRef unreachableLabel = new TACLabelRef(tree);
			TACBlock breakBlock = block.getBreakBlock();
			visitCleanup(breakBlock, null, breakBlock.getBreak());
			unreachableLabel.new TACLabel(tree);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTContinueStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef unreachableLabel = new TACLabelRef(tree);
			TACBlock continueBlock = block.getContinueBlock();
			visitCleanup(continueBlock, null, continueBlock.getContinue());
			unreachableLabel.new TACLabel(tree);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTReturnStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef unreachableLabel = new TACLabelRef(tree);
			visitCleanup(null, null);
			new TACReturn(tree, (SequenceType)node.getType(),
					tree.appendChild(0));
			unreachableLabel.new TACLabel(tree);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTThrowStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			TACLabelRef unreachableLabel = new TACLabelRef(tree);
			new TACThrow(tree, block, tree.appendChild(0));
			unreachableLabel.new TACLabel(tree);
		}
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTFinallyStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			tree.appendChild(0);
			if (node.hasFinally())
			{
				TACPhi phi = (TACPhi)tree.getLast();
				tree.appendChild(1);
				new TACBranch(tree, (TACDestination)phi.getRef());
			}
			block.getDone().new TACLabel(tree);
			block = block.getParent();
		}
		else
			block = new TACBlock(tree, block).addDone();
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTRecoverStatement node, Boolean secondVisit)
			throws ShadowException
	{
		ASTFinallyStatement parent = (ASTFinallyStatement)node.jjtGetParent();
		if (secondVisit)
		{
			tree.appendChild(0);
			if (node.hasRecover())
			{
				tree.appendChild(1);
				new TACBranch(tree, block.getDone());
			}
			if (parent.hasFinally())
			{
				block.getDone().new TACLabel(tree);
				method.setHasLandingpad();
				visitCleanup(block.getParent(), block.getDone(),
						block.getParent().getDone());
				block.getLandingpad().new TACLabel(tree);
				new TACLandingpad(tree, block);
				new TACBranch(tree, block.getUnwind());
				block.getUnwind().new TACLabel(tree);
				new TACUnwind(tree, block);
				TACLabelRef continueUnwind = block.getParent().getUnwind();
				if (continueUnwind != null)
					visitCleanup(block, block.getUnwind(), continueUnwind);
				else
				{
					visitCleanup(block, block.getUnwind());
					new TACResume(tree);
				}
				block.getCleanup().new TACLabel(tree);
				block.getCleanupPhi().new TACPhi(tree);
				block = block.getParent();
			}
		}
		else if (parent.hasFinally())
			block = new TACBlock(tree, block).addLandingpad().addUnwind().
					addCleanup().addDone();
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTCatchStatements node, Boolean secondVisit)
			throws ShadowException
	{
		ASTRecoverStatement parent = (ASTRecoverStatement)node.jjtGetParent();
		if (secondVisit)
		{
			TACOperand typeid = tree.appendChild(0);
			for (int i = 1; i <= node.getCatches(); i++)
			{
				Type type = node.jjtGetChild(i).jjtGetChild(0).getType();
				TACLabelRef catchLabel = block.getCatch(i - 1),
						skip = new TACLabelRef(tree);
				new TACBranch(tree, new TACCall(tree, block, new TACMethodRef(
						tree, Type.INT.getMatchingMethod("equal", new SequenceType(Type.INT))), typeid,
						new TACTypeId(tree, new TACClass(tree, type, method))),
						catchLabel, skip);
				catchLabel.new TACLabel(tree);
				tree.appendChild(i);
				new TACBranch(tree, block.getDone());
				skip.new TACLabel(tree);
				if (i == node.getCatches())
				{
					TACLabelRef continueUnwind = block.getUnwind();
					if (continueUnwind != null)
						new TACBranch(tree, continueUnwind); //try inside of try					
					else
					{	
						//simply break?
						new TACResume(tree);
						//new TACBranch(tree, block.getParent().getDone()); 
						//System.err.println("Trouble!");
						//rethrow if necessary!
						/*
						TACLabelRef unreachableLabel = new TACLabelRef(tree);
						new TACThrow(tree, block, typeid);
						unreachableLabel.new TACLabel(tree);
						*/						
					}
					
				}
			}
			
			/*  //old code
			if (parent.hasRecover())
			{
				block.getRecover().new TACLabel(tree);
				block = block.getParent();
			}
			*/
			
			//new code
			if (parent.hasRecover())			
				block.getRecover().new TACLabel(tree);
			
			block = block.getParent();			
		}
		else 
		{
			block = new TACBlock(tree, block);
			if (parent.hasRecover())
				block.addRecover();
			
			block.addCatches(node.getCatches());
		}
								
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTCatchStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			tree.appendChild(0);
			new TACStore(tree, new TACVariableRef(tree, method.getLocal(
					node.jjtGetChild(0).getImage())), new TACCatch(tree,
					(ExceptionType)node.jjtGetChild(0).getType()));
			tree.appendChild(1);
			method.exitScope();
		}
		else
			method.enterScope();
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTTryStatement node, Boolean secondVisit)
			throws ShadowException
	{
		ASTCatchStatements parent = (ASTCatchStatements)node.jjtGetParent();
		if (secondVisit)
		{
			tree.appendChild(0);
			new TACBranch(tree, block.getDone());
			if (parent.getCatches() > 0 )
			{
				method.setHasLandingpad();
				block.getLandingpad().new TACLabel(tree);
				new TACLandingpad(tree, block);
				new TACBranch(tree, block.getUnwind());
				block.getUnwind().new TACLabel(tree);
				new TACTypeId(tree, new TACUnwind(tree, block));
				block = block.getParent();
			}
		}
		else if (parent.getCatches() > 0)
		{	
			//block.addLandingpad().addUnwind(); //unwind should be added to the ASTCatchStatement's block
			//block = new TACBlock(tree, block); //should the landing pad be on the previous block too?
			//old code
			block = new TACBlock(tree, block).addLandingpad().addUnwind();
		}
		return POST_CHILDREN;
	}

/*
	@Override
	public Object visit(ASTTryStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			tree.appendChild(0);
			if (node.hasFinally())
			{
				TACLabelRef doneLabel = new TACLabelRef(tree);
				visitCleanup(block, null, doneLabel);
				block.getCleanup().new TACLabel(tree);
				block.getCleanupPhi().new TACPhi(tree);
				tree.appendChild(1);
				new TACBranch(tree, block.getCleanupPhi());
				doneLabel.new TACLabel(tree);
				block = block.getParent();
			}
		}
		else if (node.hasFinally())
			block = new TACBlock(tree, block).addCleanup();
		return POST_CHILDREN;
	}

	@Override
	public Object visit(ASTInnerTryStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			tree.appendChild(0);
			TACLabelRef doneLabel = new TACLabelRef(tree);
			new TACBranch(tree, doneLabel);
			if (node.hasCatches())
			{
				block.getLandingpad().new TACLabel(tree);
				new TACLandingpad(tree, block);
				new TACBranch(tree, block.getUnwind());
				block.getUnwind().new TACLabel(tree);
				new TACUnwind(tree, block);
				if (block.getParent().hasUnwind())
					visitCleanup(block.getParent(), null,
							block.getParent().getUnwind());
				else
				{
					visitCleanup(block.getParent());
					new TACResume(tree);
				}
				for (int i = 0; i < node.getCatches(); i++)
				{
					block.getCatch(i).new TACLabel(tree);
					tree.appendChild(i + 1);
					new TACBranch(tree, doneLabel);
				}
			}
			if (node.hasRecover())
			{
				block.getRecover().new TACLabel(tree);
				tree.appendChild(tree.getNumChildren() - 1);
				new TACBranch(tree, doneLabel);
			}
			doneLabel.new TACLabel(tree);
			block = block.getParent();
		}
		else
		{
			block = new TACBlock(tree, block);
			if (node.hasCatches())
			{
				method.setHasLandingpad();
				block.addLandingpad().addUnwind().addCatches(node.getCatches());
			}
			if (node.hasRecover())
				block.addRecover();
		}
		return POST_CHILDREN;
	}
	@Override
	public Object visit(ASTCatchStatement node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			new TACStore(tree, (TACReference)tree.appendChild(0),
					new TACCatch(tree, (ExceptionType)node.getType()));
			tree.appendChild(1);
			method.exitScope();
		}
		else
			method.enterScope();
		return POST_CHILDREN;
	}
*/

	@SuppressWarnings("unused")
	private void visitCleanup(TACBlock lastBlock)
	{
		visitCleanup(lastBlock, null);
	}
	private void visitCleanup(TACBlock lastBlock, TACLabelRef currentLabel)
	{
		if (lastBlock != null)
			lastBlock = lastBlock.getParent();
		TACBlock currentBlock = block.getCleanupBlock(lastBlock);
		if (currentBlock != lastBlock)
		{
			TACLabelRef lastLabel = new TACLabelRef(tree);
			visitCleanup(currentBlock, lastBlock, currentLabel, lastLabel);
			lastLabel.new TACLabel(tree);
		}
	}
	private void visitCleanup(TACBlock lastBlock, TACLabelRef currentLabel,
			TACLabelRef lastLabel)
	{
		if (lastBlock != null)
			lastBlock = lastBlock.getParent();
		TACBlock currentBlock = block.getCleanupBlock(lastBlock);
		if (currentBlock != lastBlock)
			visitCleanup(currentBlock, lastBlock, currentLabel, lastLabel);
		else
			new TACBranch(tree, lastLabel);
	}
	private void visitCleanup(TACBlock currentBlock, TACBlock lastBlock,
			TACLabelRef currentLabel, TACLabelRef lastLabel)
	{
		if (currentLabel == null)
		{
			currentLabel = new TACLabelRef(tree);
			new TACBranch(tree, currentLabel);
			currentLabel.new TACLabel(tree);
		}
		TACBlock nextBlock;
		while ((nextBlock = currentBlock.getNextCleanupBlock(lastBlock)) !=
				lastBlock)
		{
			TACLabelRef nextLabel = new TACLabelRef(tree);
			new TACBranch(tree, currentBlock.getCleanup());
			currentBlock.getCleanupPhi().addEdge(nextLabel, currentLabel);
			nextLabel.new TACLabel(tree);
			currentBlock = nextBlock;
			currentLabel = nextLabel;
		}
		new TACBranch(tree, currentBlock.getCleanup());
		currentBlock.getCleanupPhi().addEdge(lastLabel, currentLabel);
	}

//		tree = tree.next();
//		TACLabelRef doneLabel = new TACLabelRef(tree);
//		int index;
//
//		TACBlock saveBlock = block;
//
//		TACBlock outerBlock = new TACBlock(tree, saveBlock);
//		if (node.hasFinally())
//			outerBlock.addCleanup();
//
//		TACBlock innerBlock = new TACBlock(tree, outerBlock);
//		if (node.hasRecover())
//			innerBlock.addRecover();
//		innerBlock.addCatches(node.getCatches());
//		if (node.hasCatches())
//			innerBlock.addLandingpad();
//
//		block = innerBlock;
//		walk(node.jjtGetChild(0));
//		block = outerBlock;
//		for (index = 1; index <= node.getCatches(); index++)
//			walk(node.jjtGetChild(index));
//		if (node.hasRecover())
//			walk(node.jjtGetChild(index++));
//		block = saveBlock;
//		if (node.hasFinally())
//			walk(node.jjtGetChild(index++));
//
//		index = 0;
//		tree.appendChild(index);
//		new TACBranch(tree, doneLabel);
//		if (node.hasCatches())
//		{
//			innerBlock.getLandingpad().new TACLabel(tree);
//			new TACLandingpad(tree, innerBlock);
//			for (int i = 1; i <= node.getCatches(); i++)
//			{
//				innerBlock.getCatch(i).new TACLabel(tree);
//				new TACStore(tree, new TACVariableRef(tree, null),
//						new TACCatch(tree,
//						(ExceptionType)node.jjtGetChild(i).getType()));
//				tree.appendChild(++index);
//				new TACBranch(tree, doneLabel);
//			}
//		}
//		if (node.hasRecover())
//		{
//			innerBlock.getRecover().new TACLabel(tree);
//			tree.appendChild(++index);
//			new TACBranch(tree, doneLabel);
//		}
//		doneLabel.new TACLabel(tree);
//		if (node.hasFinally())
//		{
//			doneLabel = new TACLabelRef(tree);
//			outerBlock.getCleanup().new TACLabel(tree);
//			tree.appendChild(++index);
//			new TACBranch(tree, doneLabel);
//			doneLabel.new TACLabel(tree);
//		}
//
//		return NO_CHILDREN;
//
//		tree.appendChild(index++);
//		new TACBranch(tree, endLabel);
//		for (int i = 0; i < node.getCatches(); i++)
//		{
//			index++;
//			index++;
//			new TACBranch(tree, endLabel);
//		}
//		if (node.hasRecover())
//		{
//			block.getRecover().new TACLabel(tree);
//			tree.appendChild(index++);
//			new TACBranch(tree, endLabel);
//		}
//		if (node.hasFinally())
//		{
//			block.getCleanup().new TACLabel(tree);
//			index++;
//		}
//		else
//			endLabel.new TACLabel(tree);
//		block = block.getParent();
//
//		block = block.getParent();
//		return NO_CHILDREN;
//
//		if (secondVisit)
//		{
//			int index = 0;
//			TACLabelRef endLabel = null;
//			if (node.hasRecover())
//				tree.append(block.getRecover());
//			if (node.hasFinally())
//				tree.append(endLabel = block.getCleanup());
//			if (endLabel == null)
//				endLabel = new TACLabelRef(tree);
//			tree.appendChild(index++);
//			new TACBranch(tree, endLabel);
//			for (int i = 0; i < node.getCatches(); i++)
//			{
//				index++;
//				index++;
//				new TACBranch(tree, endLabel);
//			}
//			if (node.hasRecover())
//			{
//				block.getRecover().new TACLabel(tree);
//				tree.appendChild(index++);
//				new TACBranch(tree, endLabel);
//			}
//			if (node.hasFinally())
//			{
//				block.getCleanup().new TACLabel(tree);
//				index++;
//			}
//			else
//				endLabel.new TACLabel(tree);
//			block = block.getParent();
//		}
//		else
//		{
//			if (node.getCatches() != 0)
//				throw new UnsupportedOperationException();
//			block = new TACBlock(block);
//			if (node.hasRecover())
//				block.addRecover();
//			if (node.hasFinally())
//				block.addCleanup();
//		}
//		return POST_CHILDREN;

	private void visitConstant(TACConstant constantRef, Node constantNode)
			throws ShadowException
	{
		TACTree saveTree = tree;
		tree = new TACTree(1);
		walk(constantNode.jjtGetChild(0));
		tree.done();
		constantRef.append(tree);
		module.addConstant(constantRef);
		tree = saveTree;
	}
	private void visitMethod(MethodSignature methodSignature)
			throws ShadowException
	{
		TACTree saveTree = tree;
		TACMethod method = this.method = new TACMethod(methodSignature);
		TACMethodRef methodRef = method.getMethod();
		if (module.isClass()/* && !methodRef.isNative()*/)
		{
			block = new TACBlock(tree = new TACTree(1));
			if (implicitCreate = methodRef.isCreate())
			{
				Type type = methodRef.getPrefixType();
				if (type.hasOuter())
					new TACStore(tree,
							new TACFieldRef(tree, new TACVariableRef(tree,
									method.getThis()),
									new SimpleModifiedType(type.getOuter()),
									"this"),
							new TACVariableRef(tree,
									method.getParameter("outer")));
				if (type.isParameterized())
					for (ModifiedType typeParam : type.getTypeParameters())
						new TACStore(tree,
								new TACFieldRef(tree, new TACVariableRef(tree,
										method.getThis()),
										new SimpleModifiedType(Type.CLASS),
										typeParam.getType().getTypeName()),
								new TACVariableRef(tree,
										method.getParameter(typeParam.
												getType().getTypeName())));
				for (Node field : type.getFields().values())
					if (!field.getModifiers().isConstant())
						walk(field);
			}
			if (methodSignature.getNode() == null)
			{
				method.addParameters();
				TACFieldRef field = new TACFieldRef(tree, new TACVariableRef(
						tree, method.getThis()), methodRef.getName());
				if (methodRef.isGet())
					new TACReturn(tree, methodRef.getReturnTypes(),
							new TACLoad(tree, field));
				else if (methodRef.isSet())
				{
					TACVariable value = null;
					for (TACVariable parameter : method.getParameters())
						value = parameter;
					new TACStore(tree, field, new TACVariableRef(tree, value));
					new TACReturn(tree, methodRef.getReturnTypes());
				}
				else
					new TACReturn(tree, methodRef.getReturnTypes());
			}	
			else if (methodRef.isNative())
				walk(methodSignature.getNode().jjtGetChild(0).jjtGetChild(0));
			else if (methodRef.isWrapper())
			{
				TACMethodRef wrapped = methodRef.getWrapped();
				SequenceType fromTypes = methodRef.getParameterTypes(),
						toTypes = wrapped.getParameterTypes();
				Iterator<TACVariable> fromArguments = method.addParameters().
						getParameters().iterator();
				List<TACOperand> toArguments = new ArrayList<TACOperand>(
						toTypes.size());
				for (int i = 0; i < toTypes.size(); i++)
				{
					TACOperand argument =
							new TACVariableRef(tree, fromArguments.next());
					if (!fromTypes.getType(i).isSubtype(toTypes.getType(i)))
						argument = new TACCast(tree, toTypes.get(i), argument);
					toArguments.add(argument);
				}
				new TACReturn(tree, methodRef.getReturnTypes(),
						new TACCall(tree, block, new TACMethodRef(tree,
									wrapped), toArguments));
			}
			else
				walk(methodSignature.getNode());
			if (implicitCreate)
			{
				ClassType thisType = (ClassType)methodRef.getPrefixType(),
						superType = thisType.getExtendType();
				if (superType != null)
					new TACCall(method, block, new TACMethodRef(method,
							new MethodType(superType, new Modifiers()),
							"create"), new TACVariableRef(method,
							method.getThis()));
				new TACInit(method, thisType);
			}
			tree.done();
			method.append(tree);
		}
		module.addMethod(method);
		block = null;
		this.method = null;
		tree = saveTree;
	}

	/*
	private void visitUnaryOperation(SimpleNode node)
	{
		
		new TACUnary(tree, node.getImage().charAt(0), tree.appendChild(0));
	}
	*/

	/*
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
	*/
	
	private void visitBooleanOperation(Node node)
	{
		int child = 0;		
		String image = node.getImage();
		TACOperand current = null;
			while( current == null )
				current = tree.appendChild(child++);
		for( int index = 0; index < node.getImage().length(); index++ )
		{
			char op = image.charAt(index);
			TACOperand next = null;
			while( next == null )
				next = tree.appendChild(child++);
			
			TACBinary.Boolean connector = null;
			switch( op )
			{
			case 'a': connector = TACBinary.Boolean.AND; break;
			case 'o': connector = TACBinary.Boolean.OR; break;
			case 'x': connector = TACBinary.Boolean.XOR; break;
			default: throw new IllegalArgumentException("Operator " + op + " is not a valid boolean operator");			
			}
									
			current = new TACBinary(tree, current, connector, next);
		}	
	}
	
	private void visitBinaryOperation(OperationNode node)
	{	
		int child = 0;		
		String image = node.getImage();
		TACOperand current = null;
			while( current == null )
				current = tree.appendChild(child++);
		for( int index = 0; index < node.getOperations().size(); index++ )
		{
			char op = image.charAt(index);
			TACOperand next = null;
			while( next == null )
				next = tree.appendChild(child++);
			//BinaryOperation operation = new BinaryOperation( current, next, c );
			MethodSignature signature = node.getOperations().get(index);
			boolean isCompare = ( op == '<' || op == '>' || op == '{' || op == '}' );
			Type currentType = current.getType();
			if( currentType instanceof PropertyType )
				currentType = ((PropertyType)currentType).getGetType().getType();
			
			if( currentType.isPrimitive() && signature.getModifiers().isNative() ) //operation based on method
				current = new TACBinary(tree, current, signature, op, next, isCompare );
			else
			{
				//comparisons will always give positive, negative or zero integer
				//must be compared to 0 with regular int comparison to work
				if( isCompare )
				{
					TACVariableRef var = new TACVariableRef(tree,
							method.addTempLocal(new SimpleModifiedType(Type.INT)));
					new TACStore(tree, var, new TACCall(tree, block, new TACMethodRef(tree, current, signature), current, new TACLiteral(tree, "0")));		
					current = new TACLoad(tree, var);					
					current = new TACBinary(tree, current, Type.INT.getMatchingMethod("compare", new SequenceType(Type.INT)), op, new TACLiteral(tree, "0"), true );
				}
				else
				{
					TACVariableRef var = new TACVariableRef(tree,
							method.addTempLocal(node));
					new TACStore(tree, var, new TACCall(tree, block, new TACMethodRef(tree, current, signature), current, next));		
					current = new TACLoad(tree, var);
				}
			}			
		}
	}

	private void visitExpression(OperationNode node)
	{
		TACOperand value = tree.appendChild(2);
		TACReference var = (TACReference)tree.appendChild(0);
		char operation = node.jjtGetChild(1).getImage().charAt(0);
		if (operation != '=')
			value = new TACBinary(tree, var, node.getOperations().get(0), operation, value);
		new TACStore(tree, var, value);
	}

	private void visitLoop()
	{
		block = new TACBlock(tree, block).addBreak().addContinue();
	}
	private void visitLoop(int condition, int body, int update, boolean force)
	{
		TACLabelRef bodyLabel = new TACLabelRef(tree),
				updateLabel = block.getContinue(),
				conditionLabel = condition != update ?
						new TACLabelRef(tree) : updateLabel,
				endLabel = block.getBreak();
		new TACBranch(tree, force ? bodyLabel : conditionLabel);
		bodyLabel.new TACLabel(tree);
		tree.appendChild(body);
		if (condition != update)
		{
			new TACBranch(tree, updateLabel);
			updateLabel.new TACLabel(tree);
			tree.appendChild(update);
		}
		new TACBranch(tree, conditionLabel);
		conditionLabel.new TACLabel(tree);
		new TACBranch(tree, tree.appendChild(condition), bodyLabel, endLabel);
		endLabel.new TACLabel(tree);
		block = block.getParent();
	}

	private TACOperand visitArrayAllocation(ArrayType type,
			List<TACOperand> sizes)
	{
		TACOperand baseClass = new TACClass(tree, type.getBaseType(), method);
		TACNewArray alloc = new TACNewArray(tree, type, baseClass,
				sizes.subList(0, type.getDimensions()));
		sizes = sizes.subList(type.getDimensions(), sizes.size());
		if (!sizes.isEmpty())
		{
			TACReference index = new TACVariableRef(tree,
					method.addTempLocal(new SimpleModifiedType(Type.INT)));
			new TACStore(tree, index, new TACLiteral(tree, "0"));
			TACLabelRef bodyLabel = new TACLabelRef(tree),
					condLabel = new TACLabelRef(tree),
					endLabel = new TACLabelRef(tree);
			new TACBranch(tree, condLabel);
			bodyLabel.new TACLabel(tree);
			new TACStore(tree, new TACArrayRef(tree, alloc, index),
					visitArrayAllocation((ArrayType)type.getBaseType(), sizes));
			new TACStore(tree, index, new TACBinary(tree, index, Type.INT.getMatchingMethod("add", new SequenceType(Type.INT)), '+',
					new TACLiteral(tree, "1")));
			new TACBranch(tree, condLabel);
			condLabel.new TACLabel(tree);
			new TACBranch(tree, new TACSame(tree, index, alloc.getTotalSize()),
					endLabel, bodyLabel);
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

	private TACOperand getDefaultValue(ModifiedType type)
	{
		if (type.getType().equals(Type.BOOLEAN))
			return new TACLiteral(tree, "false");
		if (type.getType().equals(Type.CODE))
			return new TACLiteral(tree, "'\0'");
		if (type.getType().equals(Type.UBYTE))
			return new TACLiteral(tree, "0uy");
		if (type.getType().equals(Type.BYTE))
			return new TACLiteral(tree, "0y");
		if (type.getType().equals(Type.USHORT))
			return new TACLiteral(tree, "0us");
		if (type.getType().equals(Type.SHORT))
			return new TACLiteral(tree, "0s");
		if (type.getType().equals(Type.UINT))
			return new TACLiteral(tree, "0ui");
		if (type.getType().equals(Type.INT))
			return new TACLiteral(tree, "0i");
		if (type.getType().equals(Type.ULONG))
			return new TACLiteral(tree, "0ul");
		if (type.getType().equals(Type.LONG))
			return new TACLiteral(tree, "0l");
//		if (!type.getModifiers().isNullable())
//			throw new IllegalArgumentException();
		return new TACCast(tree, type, new TACLiteral(tree, "null"));
	}

	@Override
	public Object visit(ASTBrackets node, Boolean secondVisit)
			throws ShadowException
	{
		//builds onto a type only, no action required
		return PRE_CHILDREN;
	}

	@Override
	public Object visit(ASTDestroy node, Boolean secondVisit)
			throws ShadowException
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Object visit(ASTForeachInit node, Boolean secondVisit)
			throws ShadowException
	{
		throw new UnsupportedOperationException();
	}
	@Override
	public Object visit(ASTInlineResults node, Boolean secondVisit)
			throws ShadowException
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Object visit(ASTSequenceRightSide node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			List<TACOperand> sequence =
					new ArrayList<TACOperand>(node.getType().size());
			
			for (int index = 0; index < node.getType().size(); index++ )				
					sequence.add(tree.appendChild(index));
			new TACSequence(tree, sequence);	
		}
			
		return POST_CHILDREN; 
	}	
	
	@Override
	public Object visit(ASTSequenceLeftSide node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			List<TACOperand> sequence =
					new ArrayList<TACOperand>(node.getUsedItems().size());
			int index = 0;
			for (boolean used : node.getUsedItems())
				if (used)
					sequence.add(tree.appendChild(index++));
				else
					sequence.add(new TACVariableRef(tree,
							method.addTempLocal(new SimpleModifiedType(
									Type.OBJECT,
									new Modifiers(Modifiers.NULLABLE)))));
			new TACSequence(tree, sequence);	
		}
			
		return POST_CHILDREN; 		
	}
	@Override
	public Object visit(ASTSequenceVariable node, Boolean secondVisit)
			throws ShadowException
	{
		if (secondVisit)
		{
			String name = node.getImage();
			TACReference ref = new TACVariableRef(tree, method.addLocal(node, name));
			//default values since storage happens after the right side is dealt with			
			//new TACStore(tree, ref, getDefaultValue(node));			
		}
		return POST_CHILDREN;
	}
	
	@Override
	public Object visit(ASTRightSide node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
	}
	@Override
	public Object visit(ASTViewBodyDeclaration node, Boolean secondVisit)
			throws ShadowException
	{
		throw new UnsupportedOperationException();
	}
	@Override
	public Object visit(ASTFreezeExpression node, Boolean secondVisit)
			throws ShadowException
	{
		return PRE_CHILDREN;
		//throw new UnsupportedOperationException();
		//TODO: Make freeze work
	}
}
