/*
 * Copyright 2017 Team Shadow
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package shadow.typecheck;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import shadow.ShadowException;
import shadow.interpreter.*;
import shadow.parse.Context;
import shadow.parse.Context.AssignmentKind;
import shadow.parse.ShadowParser;
import shadow.parse.ShadowParser.*;
import shadow.typecheck.TypeCheckException.Error;
import shadow.typecheck.type.InstantiationException;
import shadow.typecheck.type.*;

import java.util.*;

public class StatementChecker extends ScopedChecker {
  /* Stack for current prefix (needed for arbitrarily long chains of expressions). */
  private final LinkedList<Context> curPrefix = new LinkedList<>();

  public StatementChecker(Package packageTree, ErrorReporter reporter) {
    super(packageTree, reporter);
  }

  public void check(Context node) throws ShadowException {
    // Check the whole class
    visit(node);
    printAndReportErrors();
  }

  // Important!  Set the current type on entering the body, not the declaration, otherwise extends
  // and imports are improperly checked with the wrong outer class

  @Override
  public Void visitClassOrInterfaceBody(ShadowParser.ClassOrInterfaceBodyContext ctx) {
    currentType = ((Context) ctx.getParent()).getType(); // get type from declaration

    for (InterfaceType interfaceType : currentType.getInterfaces())
      currentType.addUsedType(interfaceType);

    if (currentType instanceof ClassType classType) {
      currentType.addUsedType(classType.getExtendType());
    }

    visitChildren(ctx);

    if (currentType instanceof ClassType classType && currentType.isParameterized()) {
      Set<Type> partiallyInstantiated = currentType.getPartiallyInstantiatedClasses();
      for (Type type : partiallyInstantiated) classType.addDependency(new SimpleModifiedType(type));
    }

    currentType = currentType.getOuter();
    return null;
  }

  public void visitMethodPost() {
    currentMethod.removeFirst();
    closeScope();
  }

  public void visitMethodPre(Context node) {
    MethodSignature signature;

    if (node instanceof ShadowParser.LocalMethodDeclarationContext
        || node instanceof ShadowParser.InlineMethodDefinitionContext) {
      if (node instanceof ShadowParser.InlineMethodDefinitionContext)
        signature =
            new MethodSignature(
                currentType, "", node.getModifiers(), node.getDocumentation(), node);
      else
        signature =
            new MethodSignature(
                currentType,
                ((ShadowParser.LocalMethodDeclarationContext) node)
                    .methodDeclarator()
                    .generalIdentifier()
                    .getText(),
                node.getModifiers(),
                node.getDocumentation(),
                node);
      node.setSignature(signature);
      MethodType methodType = signature.getMethodType();

      if (node instanceof ShadowParser.InlineMethodDefinitionContext) methodType.setInline(true);

      node.setType(methodType);
      // what modifiers (if any) are allowed for a local method declaration?
    } else {
      signature = node.getSignature();
    }

    for (ModifiedType modifiedType : signature.getParameterTypes()) {
      currentType.addUsedType(modifiedType.getType());
    }

    for (ModifiedType modifiedType : signature.getReturnTypes())
      currentType.addUsedType(modifiedType.getType());


    // Done here because AttributeInvocationContext doesn't have references to these
    // AttributeInvocation objects
    for (AttributeInvocation attribute : signature.getAttributes()) {
      attribute.update(packageTree, this.getErrorReporter());
    }

    currentMethod.addFirst(node);
    openScope();
  }

  @Override
  public Void visitSwitchStatement(ShadowParser.SwitchStatementContext ctx) {
    visitChildren(ctx);

    int defaultCounter = 0;
    Type type = ctx.conditionalExpression().getType();
    if (!type.isIntegral() && !type.isString() && !(type instanceof EnumType)) {
      addError(
          ctx,
          Error.INVALID_TYPE,
          "Supplied type "
              + type
              + " cannot be used in switch statement, only integral, String, and enum types allowed",
          type);
    }

    for (ShadowParser.SwitchLabelContext label : ctx.switchLabel()) {
      if (label.getType() != null) { // default label should have null type
        if (!label.getType().isSubtype(type)) {
          addError(
              label,
              Error.INVALID_LABEL,
              "Label type " + label.getType() + " does not match switch type " + type,
              label.getType(),
              type);
        }
      } else {
        defaultCounter++;
      }
    }

    if (defaultCounter > 1)
      addError(ctx, Error.INVALID_LABEL, "Switch cannot have multiple default labels");
    else if (defaultCounter == 1) ctx.hasDefault = true;

    return null;
  }

  @Override
  public Void visitSwitchLabel(ShadowParser.SwitchLabelContext ctx) {
    visitChildren(ctx);

    if (!ctx.primaryExpression().isEmpty()) {
      Type result = null;

      for (ShadowParser.PrimaryExpressionContext child : ctx.primaryExpression()) {
        Type type = child.getType();

        if (result == null) result = type;
        else if (result.isSubtype(type)) result = type;
        else if (!type.isSubtype(result)) { // neither is subtype of other, panic!
          addError(
              child,
              Error.MISMATCHED_TYPE,
              "Supplied type " + type + " does not match type " + result + " in switch label",
              type,
              result);
          result = Type.UNKNOWN;
          break;
        }

        if (!child.getModifiers().isConstant())
          addError(child, Error.INVALID_TYPE, "Value supplied as label must be constant");
      }

      ctx.setType(result);
      ctx.addModifiers(Modifiers.CONSTANT);
    }

    return null;
  }

  @Override
  public Void visitBlock(ShadowParser.BlockContext ctx) {
    openScope();
    visitChildren(ctx);
    closeScope();

    return null;
  }

  @Override
  public Void visitMethodDeclaration(ShadowParser.MethodDeclarationContext ctx) {
    visitMethodPre(ctx);
    visitChildren(ctx);
    visitMethodPost();

    return null;
  }

  @Override
  public Void visitLocalMethodDeclaration(ShadowParser.LocalMethodDeclarationContext ctx) {
    addSymbol(ctx.methodDeclarator().generalIdentifier().getText(), ctx);
    visitMethodPre(ctx);
    visitChildren(ctx);
    visitMethodPost();

    return null;
  }

  @Override
  public Void visitFormalParameters(ShadowParser.FormalParametersContext ctx) {
    boolean local =
        (ctx.getParent() instanceof ShadowParser.InlineMethodDefinitionContext)
            || (ctx.getParent().getParent() instanceof ShadowParser.LocalMethodDeclarationContext);

    if (local) {
      visitChildren(ctx);

      SequenceType type = new SequenceType();
      for (ShadowParser.FormalParameterContext parameter : ctx.formalParameter()) {
        if (parameter.getType() instanceof SingletonType)
          addError(
              parameter, Error.INVALID_PARAMETERS, "Cannot define method with singleton parameter");
        type.add(parameter);
      }

      ctx.setType(type);
    } else {
      // class methods have their types constructed
      // but still need to have parameters added to the symbol table
      MethodType methodType = currentMethod.getFirst().getSignature().getMethodType();
      for (String symbol : methodType.getParameterNames())
        addSymbol(symbol, methodType.getParameterType(symbol));
    }

    return null;
  }

  @Override
  public Void visitFormalParameter(ShadowParser.FormalParameterContext ctx) {
    visitChildren(ctx);

    Type type = ctx.type().getType();
    ctx.setType(type);

    addSymbol(ctx.Identifier().getText(), ctx);

    return null;
  }

  @Override
  public Void visitCreateDeclaration(ShadowParser.CreateDeclarationContext ctx) {
    visitMethodPre(ctx);
    visitChildren(ctx);

    // Check for an explicit call to the parent class' create().
    // For .meta files and imports, we trust that the create() is properly implemented elsewhere
    if (currentType instanceof ClassType classType
        && !ctx.getSignature().isImport()
        && !ctx.isFromMetaFile()) {
      ClassType parentType = classType.getExtendType();

      boolean explicitCreate =
          ctx.createBlock() != null && ctx.createBlock().explicitCreateInvocation() != null;

      if (!explicitCreate && parentType != null) {
        // only worry if there is no explicit invocation
        // explicit invocations are handled separately
        boolean foundDefault = false;
        for (MethodSignature method : parentType.getMethodOverloads("create")) {
          if (method.getParameterTypes().isEmpty()) {
            foundDefault = true;
            break;
          }
        }

        if (!foundDefault)
          addError(
              ctx,
              Error.MISSING_CREATE,
              "Explicit create invocation is missing, and parent class "
                  + parentType
                  + " does not implement the default create",
              parentType);
      }
    }

    visitMethodPost();
    return null;
  }

  @Override
  public Void visitCreateBlock(ShadowParser.CreateBlockContext ctx) {
    openScope();
    visitChildren(ctx);
    closeScope();

    return null;
  }

  @Override
  public Void visitDestroyDeclaration(ShadowParser.DestroyDeclarationContext ctx) {
    visitMethodPre(ctx);
    visitChildren(ctx);
    visitMethodPost();

    return null;
  }

  @Override
  public Void visitReferenceType(ShadowParser.ReferenceTypeContext ctx) {
    visitChildren(ctx);

    Context child = (Context) ctx.getChild(0); // either primitive type or class type
    Type type = child.getType();

    int dimensions = getDimensions(ctx);
    if (dimensions != 0) type = new ArrayType(type, dimensions, false);

    ctx.setType(type);

    ctx.addModifiers(Modifiers.TYPE_NAME);

    return null;
  }

  @Override
  public Void visitLocalVariableDeclaration(ShadowParser.LocalVariableDeclarationContext ctx) {
    visitChildren(ctx);

    Type type;
    boolean isVar = false;

    if (ctx.type() != null) {
      type = ctx.type().getType();
      if (type instanceof AttributeType)
        addError(
                ctx.type(),
                Error.INVALID_TYPE,
                "Variable cannot be declared with attribute type");
    }
    else { // var type
      type = Type.VAR;
      isVar = true;
    }

    ctx.setType(type);

    // Add variables
    for (ShadowParser.VariableDeclaratorContext declarator : ctx.variableDeclarator()) {
      if (isVar) {
        if (declarator.conditionalExpression() != null) { // Has initializer
          type = declarator.conditionalExpression().getType();
          if (type.equals(Type.NULL)) {
            type = Type.UNKNOWN;
            addError(
                    declarator,
                    Error.UNDEFINED_TYPE,
                    "Variable declared with var cannot be initialized to null");
          }
        } else {
          type = Type.UNKNOWN;
          addError(
              declarator,
              Error.UNDEFINED_TYPE,
              "Variable declared with var has no initializer to infer type from");
        }
      }

      if (ctx.getModifiers().isNullable() && type instanceof ArrayType arrayType) {
        type = arrayType.convertToNullable();
      }

      declarator.setType(type);
      declarator.addModifiers(ctx.getModifiers());
      addSymbol(declarator.generalIdentifier().getText(), declarator); // add to local scope
    }

    checkInitializers(ctx.variableDeclarator());

    return null;
  }

  private void checkInitializers(List<ShadowParser.VariableDeclaratorContext> declarators) {
    for (ShadowParser.VariableDeclaratorContext declarator : declarators) {
      if (declarator.conditionalExpression() != null) { // has initializer
        addErrors(
            declarator, isValidInitialization(declarator, declarator.conditionalExpression()));
        Type leftType = declarator.getType();
        Type rightType = declarator.conditionalExpression().getType();
        // let unbound method type know what signature it will eventually be bound to
        if (rightType instanceof UnboundMethodType unboundType && leftType instanceof MethodReferenceType) {
          MethodType methodType = ((MethodReferenceType) leftType).getMethodType();
          MethodSignature signature =
              unboundType
                  .getOuter()
                  .getMatchingMethod(unboundType.getTypeName(), methodType.getParameterTypes());
          unboundType.setKnownSignature(signature);
        }
      } else if (declarator.getModifiers().isConstant()) // only fields are ever constant
      addError(
            declarator,
            Error.INVALID_MODIFIER,
            "Variable declared with modifier constant must have an initializer");
    }
  }

  @Override
  public Void visitFieldDeclaration(ShadowParser.FieldDeclarationContext ctx) {
    visitChildren(ctx);
    checkInitializers(ctx.variableDeclarator()); // check all initializers
    return null;
  }




  @Override
  public Void visitRelationalExpression(ShadowParser.RelationalExpressionContext ctx) {
    visitChildren(ctx);

    Type result = ((Context) ctx.getChild(0)).getType();

    for (int i = 1; i < ctx.getChildCount(); i += 2) {
      String symbol = ctx.getChild(i).getText();
      Context currentNode = (Context) ctx.getChild(i + 1);
      Type current = currentNode.getType();

      if (result.hasUninstantiatedInterface(Type.CAN_COMPARE)) {
        SequenceType argument = new SequenceType(currentNode);

        MethodSignature signature = setMethodType(ctx, result, "compare", argument);
        if (signature != null) {
          ctx.addOperation(signature);
        } else {
          addError(
              ctx,
              Error.INVALID_TYPE,
              "Operator " + symbol + " not defined on types " + result + " and " + current,
              result,
              current);
          result = Type.UNKNOWN;
          break;
        }
      } else {
        addError(
            ctx,
            Error.INVALID_TYPE,
            "Operator " + symbol + " not defined on types " + result + " and " + current,
            result,
            current);
        result = Type.UNKNOWN;
        break;
      }

      result = Type.BOOLEAN; // boolean after one comparison
    }

    ctx.setType(result); // also propagates type up if only one child
    if (ctx.getChildCount() == 1) // can make ASSIGNABLE (if only one child)
      ctx.addModifiers(((Context) ctx.getChild(0)).getModifiers());

    return null;
  }

  @Override
  public Void visitConcatenationExpression(ShadowParser.ConcatenationExpressionContext ctx) {
    visitChildren(ctx);

    Type result = null;
    boolean first = true;

    for (ShadowParser.ShiftExpressionContext child : ctx.shiftExpression()) {
      if (first) {
        result = child.getType();
        first = false;
      } else {
        result = Type.STRING;
        if (child.getType() instanceof SequenceType) {
          addError(
              child,
              Error.INVALID_TYPE,
              "Cannot apply operator # to sequence type " + child.getType(),
              child.getType(),
              result);
          result = Type.UNKNOWN;
        }
      }
    }

    ctx.setType(result); // Propagates type up if only one child
    if (ctx.getChildCount() == 1) // Can make ASSIGNABLE (if only one child)
      ctx.addModifiers(((Context) ctx.getChild(0)).getModifiers());

    return null;
  }

  @Override
  public Void visitEqualityExpression(ShadowParser.EqualityExpressionContext ctx) {
    visitChildren(ctx);

    Context first = ((Context) ctx.getChild(0));
    Type resultType = first.getType();

    for (int i = 1; i < ctx.getChildCount(); i += 2) {
      String symbol = ctx.getChild(i).getText();
      Context currentNode = (Context) ctx.getChild(i + 1);
      Type current = currentNode.getType();

      if (symbol.equals("==") || symbol.equals("!=")) {
        if (first.getModifiers().isNullable()) {
          addError(
              ctx,
              Error.INVALID_TYPE,
              "Cannot apply operator " + symbol + " to statement with nullable type");
          resultType = Type.UNKNOWN;
          break;
        }

        if (resultType.hasUninstantiatedInterface(Type.CAN_EQUAL)) {
          SequenceType argument = new SequenceType();
          argument.add(currentNode);

          MethodSignature signature = setMethodType(ctx, resultType, "equal", argument);
          if (signature != null) {
            ctx.addOperation(signature);
          } else {
            addError(
                ctx,
                Error.INVALID_TYPE,
                "Cannot apply operator " + symbol + " to types " + resultType + " and " + current,
                resultType,
                current);
            resultType = Type.UNKNOWN;
            break;
          }
        } else {
          addError(
              ctx,
              Error.INVALID_TYPE,
              "Cannot apply operator " + symbol + " to types " + resultType + " and " + current,
              resultType,
              current);
          resultType = Type.UNKNOWN;
          break;
        }
      } else if (!resultType.isSubtype(current) && !current.isSubtype(resultType)) {
        addError(
            ctx,
            Error.INVALID_TYPE,
            "Cannot apply operator " + symbol + " to types " + resultType + " and " + current,
            resultType,
            current);
        resultType = Type.UNKNOWN;
        break;
      }

      resultType = Type.BOOLEAN; // boolean after one comparison
    }

    ctx.setType(resultType); // Propagates type up if only one child
    if (ctx.getChildCount() == 1) // Can make ASSIGNABLE (if only one child)
      ctx.addModifiers(((Context) ctx.getChild(0)).getModifiers());

    return null;
  }

  private void visitBinary(Context node) {
    Type result = ((Context) node.getChild(0)).getType();
    for (int i = 1; i < node.getChildCount(); i += 2) {
      String operator = node.getChild(i).getText();
      Context currentNode = (Context) node.getChild(i + 1);
      Type current = currentNode.getType();
      InterfaceType interfaceType = null;
      String methodName = "";

      switch (operator) {
        case "+" -> {
          methodName = "add";
          interfaceType = Type.CAN_ADD;
        }
        case "-" -> {
          methodName = "subtract";
          interfaceType = Type.CAN_SUBTRACT;
        }
        case "*" -> {
          methodName = "multiply";
          interfaceType = Type.CAN_MULTIPLY;
        }
        case "/" -> {
          methodName = "divide";
          interfaceType = Type.CAN_DIVIDE;
        }
        case "%" -> {
          methodName = "modulus";
          interfaceType = Type.CAN_MODULUS;
        }
        case "<<" -> {
          methodName = "bitShiftLeft";
          interfaceType = Type.INTEGER;
        }
        case "<<<" -> {
          methodName = "bitRotateLeft";
          interfaceType = Type.INTEGER;
        }
        case ">>" -> {
          methodName = "bitShiftRight";
          interfaceType = Type.INTEGER;
        }
        case ">>>" -> {
          methodName = "bitRotateRight";
          interfaceType = Type.INTEGER;
        }
      }

      if (result.hasUninstantiatedInterface(interfaceType)) {
        // we can't know which one will work
        // so we go with an uninstantiated version and then find an appropriate signature
        SequenceType argument = new SequenceType(currentNode);

        MethodSignature signature = setMethodType(node, result, methodName, argument);
        if (signature != null) {
          result = signature.getReturnTypes().getType(0);
          node.addOperation(signature);
        } else {
          addError(
              currentNode,
              Error.INVALID_TYPE,
              "Cannot apply operator " + operator + " to types " + result + " and " + current,
              result,
              current);
          node.setType(Type.UNKNOWN);
          return;
        }
      } else {
        addError(
            currentNode,
            Error.INVALID_TYPE,
            "Cannot apply operator " + operator + " to types " + result + " and " + current,
            result,
            current);
        node.setType(Type.UNKNOWN);
        return;
      }
    }

    node.setType(result); // propagates type up if only one child
    if (node.getChildCount() == 1) // can make ASSIGNABLE (if only one child)
      node.addModifiers(((Context) node.getChild(0)).getModifiers());
  }

  @Override
  public Void visitShiftExpression(ShadowParser.ShiftExpressionContext ctx) {
    visitChildren(ctx);
    visitBinary(ctx);

    return null;
  }

  @Override
  public Void visitRotateExpression(ShadowParser.RotateExpressionContext ctx) {
    visitChildren(ctx);
    visitBinary(ctx);

    return null;
  }

  @Override
  public Void visitAdditiveExpression(ShadowParser.AdditiveExpressionContext ctx) {
    visitChildren(ctx);
    visitBinary(ctx);

    return null;
  }

  @Override
  public Void visitMultiplicativeExpression(ShadowParser.MultiplicativeExpressionContext ctx) {
    visitChildren(ctx);
    visitBinary(ctx);

    return null;
  }

  private Type visitUnary(
      ShadowParser.UnaryExpressionContext node,
      String method,
      String operator,
      InterfaceType interfaceType) {
    Type type = node.unaryExpression().getType();

    if (type.hasUninstantiatedInterface(interfaceType)) {
      MethodSignature signature = setMethodType(node, type, method, new SequenceType());
      if (signature != null) {
        type = signature.getReturnTypes().getType(0);
        node.addOperation(signature);
      } else {
        addError(
            node,
            Error.INVALID_TYPE,
            "Cannot apply "
                + operator
                + " to type "
                + type
                + " which does not implement interface "
                + interfaceType,
            type);
        type = Type.UNKNOWN;
      }
    } else {
      addError(
          node,
          Error.INVALID_TYPE,
          "Cannot apply "
              + operator
              + " to type "
              + type
              + " which does not implement interface "
              + interfaceType,
          type);
      type = Type.UNKNOWN;
    }

    return type;
  }

  @Override
  public Void visitUnaryExpression(ShadowParser.UnaryExpressionContext ctx) {
    visitChildren(ctx);

    if (ctx.unaryExpression() != null) {
      String operator = ctx.getChild(0).getText();
      Type type = ctx.unaryExpression().getType();

      switch (operator) {
        case "-" -> type = visitUnary(ctx, "negate", "unary -", Type.CAN_NEGATE);
        case "~" -> type = visitUnary(ctx, "bitComplement", "operator ~", Type.INTEGER);
        case "#" -> {
          if (type instanceof SequenceType) {
            addError(ctx, Error.INVALID_TYPE, "Cannot apply operator # to sequence type " + type);
            type = Type.UNKNOWN;
          } else {
            MethodSignature signature = setMethodType(ctx, type, "toString", new SequenceType());
            ctx.addOperation(signature); // should never be null
            type = Type.STRING;
          }
        }
        case "!" -> {
          if (!type.equals(Type.BOOLEAN)) {
            addError(
                    ctx,
                    Error.INVALID_TYPE,
                    "Cannot apply operator ! to type " + type + " which is not boolean",
                    type);
            type = Type.UNKNOWN;
          }
        }
      }

      ctx.setType(type);
    } else {
      Context child = (Context) ctx.getChild(0);
      ctx.setType(child.getType());
      ctx.addModifiers(child.getModifiers());
    }

    return null;
  }

  @Override
  public Void visitConditionalExpression(ShadowParser.ConditionalExpressionContext ctx) {
    visitChildren(ctx);

    if (ctx.getChildCount() == 1) {
      ctx.setType(ctx.coalesceExpression().getType());
      ctx.addModifiers(ctx.coalesceExpression().getModifiers());
    } else { // ternary
      ThrowOrConditionalExpressionContext first = ctx.throwOrConditionalExpression(0);
      ThrowOrConditionalExpressionContext second = ctx.throwOrConditionalExpression(1);

      Type t1 = ctx.coalesceExpression().getType();

      if (!t1.equals(Type.BOOLEAN)) {
        addError(
            ctx.coalesceExpression(),
            Error.INVALID_TYPE,
            "Supplied type "
                + t1
                + " cannot be used in the condition of a ternary operator, boolean type required",
            t1);
        ctx.setType(Type.UNKNOWN);
      } else if (first.throwStatement() != null || second.throwStatement() != null) {
        if (first.throwStatement() != null && second.throwStatement() != null) {
          addError(
              ctx,
              Error.INVALID_STRUCTURE,
              "Only one throw is allowed in the clause of a ternary operator");
          ctx.setType(Type.UNKNOWN);
        } else {
          ConditionalExpressionContext actual =
              first.throwStatement() == null
                  ? first.conditionalExpression()
                  : second.conditionalExpression();
          ctx.setType(actual.getType());
          ctx.addModifiers(actual.getModifiers());
        }
      } else {
        Type t2 = first.getType();
        Type t3 = second.getType();

        if (first.getModifiers().isNullable() || second.getModifiers().isNullable())
          ctx.addModifiers(Modifiers.NULLABLE);

        if (first.getModifiers().isNullable() != second.getModifiers().isNullable()
            && (t2 instanceof ArrayType || t3 instanceof ArrayType))
          addError(
              ctx, Error.INVALID_MODIFIER, "Cannot mix nullable and non-nullable arrays", t2, t3);

        if (first.getModifiers().isReadonly() || second.getModifiers().isReadonly())
          ctx.addModifiers(Modifiers.READONLY);
        else if (first.getModifiers().isTemporaryReadonly()
            || second.getModifiers().isTemporaryReadonly())
          ctx.addModifiers(Modifiers.TEMPORARY_READONLY);

        if (first.getModifiers().isImmutable() && second.getModifiers().isImmutable())
          ctx.addModifiers(Modifiers.IMMUTABLE);
        else if (first.getModifiers().isImmutable()
            || second
                .getModifiers()
                .isImmutable()) // immutable + regular = readonly, works for either
        ctx.addModifiers(Modifiers.READONLY);

        if (t2.isSubtype(t3)) ctx.setType(t3);
        else if (t3.isSubtype(t2)) ctx.setType(t2);
        else {
          addError(
              ctx,
              Error.MISMATCHED_TYPE,
              "Supplied type " + t2 + " must match " + t3 + " in execution of conditional operator",
              t2,
              t3);
          ctx.setType(Type.UNKNOWN);
        }
      }
    }

    return null;
  }

  @Override
  public Void visitThrowOrConditionalExpression(ThrowOrConditionalExpressionContext ctx) {
    visitChildren(ctx);

    ConditionalExpressionContext cond = ctx.conditionalExpression();
    if (cond != null) {
      ctx.setType(cond.getType());
      ctx.addModifiers(cond.getModifiers());
    }

    return null;
  }

  public void visitConditional(Context node) {
    if (node.getChildCount() == 1) {
      Context child = (Context) node.getChild(0);
      node.setType(child.getType());
      node.addModifiers(child.getModifiers());
    } else {
      Type result = null;

      for (int i = 0; i < node.getChildCount(); i += 2) {
        Context child = (Context) node.getChild(i);
        result = child.getType();

        if (result != Type.BOOLEAN) {
          addError(
              child,
              Error.INVALID_TYPE,
              "Supplied type "
                  + result
                  + " cannot be used with a logical operator, boolean type required",
              result);
          node.setType(Type.UNKNOWN);
          return;
        }
      }

      node.setType(result);
    }
  }

  @Override
  public Void visitCoalesceExpression(ShadowParser.CoalesceExpressionContext ctx) {
    visitChildren(ctx);

    if (ctx.getChildCount() == 1) {
      Context child = (Context) ctx.getChild(0);
      ctx.setType(child.getType());
      ctx.addModifiers(child.getModifiers());
    } else {
      Type result = null;
      boolean isNullable = true;
      int term = 0;

      for (ShadowParser.ConditionalOrExpressionContext child :
          ctx.conditionalOrExpression()) { // cycle through types, upgrading to broadest legal one
        Type type = child.getType();
        Modifiers modifiers = child.getModifiers();
        term++;

        if (!modifiers.isNullable()) {
          isNullable = false;
          if (term < ctx.conditionalOrExpression().size()) { // only last child can be nullable
            addError(
                child,
                Error.INVALID_TYPE,
                "Only the last term in a coalesce expression can be non-nullable");
            result = Type.UNKNOWN;
            break;
          }
        }

        if (type instanceof ArrayType)
          addError(child, Error.INVALID_TYPE, "Array type cannot be used in a coalesce expression");

        if (result == null) result = type;
        else if (result.isSubtype(type)) result = type;
        else if (!type.isSubtype(result)) { // neither is subtype of other, panic!
          addError(
              ctx,
              Error.MISMATCHED_TYPE,
              "Supplied type "
                  + type
                  + " does not match type "
                  + result
                  + " in coalesce expression",
              type,
              result);
          result = Type.UNKNOWN;
          break;
        }
      }

      ctx.setType(result);
      if (isNullable) ctx.addModifiers(Modifiers.NULLABLE);
    }

    return null;
  }

  @Override
  public Void visitConditionalOrExpression(ShadowParser.ConditionalOrExpressionContext ctx) {
    visitChildren(ctx);
    visitConditional(ctx);

    return null;
  }

  @Override
  public Void visitConditionalExclusiveOrExpression(
      ShadowParser.ConditionalExclusiveOrExpressionContext ctx) {
    visitChildren(ctx);
    visitConditional(ctx);

    return null;
  }

  @Override
  public Void visitConditionalAndExpression(ShadowParser.ConditionalAndExpressionContext ctx) {
    visitChildren(ctx);
    visitConditional(ctx);

    return null;
  }

  public void visitBitwise(Context node) {
    Type result = ((Context) node.getChild(0)).getType();
    for (int i = 1; i < node.getChildCount(); i += 2) {
      String operator = node.getChild(i).getText();
      Context currentNode = (Context) node.getChild(i + 1);
      Type current = currentNode.getType();
      String methodName = switch (operator) {
        case "|" -> "bitOr";
        case "&" -> "bitAnd";
        case "^" -> "bitXor";
        default -> "";
      };

      if (result.hasUninstantiatedInterface(Type.INTEGER)) {
        SequenceType argument = new SequenceType();
        argument.add(currentNode);

        MethodSignature signature = setMethodType(node, result, methodName, argument);
        if (signature != null) {
          result = signature.getReturnTypes().getType(0);
          node.addOperation(signature);
        } else {
          addError(
              node,
              Error.INVALID_TYPE,
              "Operator " + operator + " not defined on types " + result + " and " + current,
              result,
              current);
          node.setType(Type.UNKNOWN);
          break;
        }
      } else {
        addError(
            node,
            Error.INVALID_TYPE,
            "Operator " + operator + " not defined on types " + result + " and " + current,
            result,
            current);
        node.setType(Type.UNKNOWN);
        break;
      }
    }

    node.setType(result);
    if (node.getChildCount() == 1) // can make ASSIGNABLE (if only one child)
      node.addModifiers(((Context) node.getChild(0)).getModifiers());
  }

  @Override
  public Void visitBitwiseOrExpression(ShadowParser.BitwiseOrExpressionContext ctx) {
    visitChildren(ctx);
    visitBitwise(ctx);

    return null;
  }

  @Override
  public Void visitBitwiseExclusiveOrExpression(
      ShadowParser.BitwiseExclusiveOrExpressionContext ctx) {
    visitChildren(ctx);
    visitBitwise(ctx);

    return null;
  }

  @Override
  public Void visitBitwiseAndExpression(ShadowParser.BitwiseAndExpressionContext ctx) {
    visitChildren(ctx);
    visitBitwise(ctx);

    return null;
  }

  @Override
  public Void visitClassOrInterfaceType(ShadowParser.ClassOrInterfaceTypeContext ctx) {
    // Optimization if type already determined.
    if (ctx.getType() != null  && !(ctx.getType() instanceof UninstantiatedType))
      return null;

    visitChildren(ctx);

    String typeName = ctx.Identifier(0).getText();
    if (ctx.unqualifiedName() != null) typeName = ctx.unqualifiedName().getText() + "@" + typeName;
    Type type = lookupType(ctx, typeName);

    for (int i = 1; type != null && i < ctx.Identifier().size(); ++i) {
      typeName = ctx.Identifier(i).getText();
      type = type.getInnerType(typeName);
    }

    if (type == null) {
      ctx.setType(Type.UNKNOWN);
      addError(ctx, Error.UNDEFINED_TYPE, "Type " + typeName + " not defined in this context");
      return null;
    }

    if (!typeIsAccessible(type, currentType))
      addError(
          ctx, Error.ILLEGAL_ACCESS, "Type " + type + " not accessible from this context", type);

    if (ctx.typeArguments() != null) { // Contains type arguments.
      SequenceType arguments = (SequenceType) ctx.typeArguments().getType();
      if (type.isParameterized()) {
        SequenceType parameters = type.getTypeParameters();
        if (parameters.canAccept(arguments, SubstitutionKind.TYPE_PARAMETER)) {
          try {
            type = type.replace(parameters, arguments);
          } catch (InstantiationException e) {
            addError(
                ctx.typeArguments(),
                Error.INVALID_TYPE_ARGUMENTS,
                "Supplied type arguments "
                    + arguments.toString(
                        Type.PACKAGES | Type.TYPE_PARAMETERS | Type.PARAMETER_BOUNDS)
                    + " do not match type parameters "
                    + parameters.toString(
                        Type.PACKAGES | Type.TYPE_PARAMETERS | Type.PARAMETER_BOUNDS));
            type = Type.UNKNOWN;
          }
        } else {
          addError(
              ctx.typeArguments(),
              Error.INVALID_TYPE_ARGUMENTS,
              "Supplied type arguments "
                  + arguments.toString(Type.PACKAGES | Type.TYPE_PARAMETERS | Type.PARAMETER_BOUNDS)
                  + " do not match type parameters "
                  + parameters.toString(
                      Type.PACKAGES | Type.TYPE_PARAMETERS | Type.PARAMETER_BOUNDS));
          type = Type.UNKNOWN;
        }
      } else {
        addError(
            ctx.typeArguments(),
            Error.UNNECESSARY_TYPE_ARGUMENTS,
            "Type arguments supplied for non-parameterized type " + type,
            type);
        type = Type.UNKNOWN;
      }
    } else if (type.isParameterized()) { // Parameterized but no parameters!
      addError(
          ctx,
          Error.MISSING_TYPE_ARGUMENTS,
          "Type arguments are not supplied for parameterized type " + type);
      type = Type.UNKNOWN;
    }

    // After updating type parameters
    if (currentType instanceof ClassType) currentType.addUsedType(type);

    // Set the type now that it has been fully initialized.
    ctx.setType(type);
    return null;
  }

  @Override
  public Void visitArguments(ShadowParser.ArgumentsContext ctx) {
    visitChildren(ctx);

    SequenceType sequence = new SequenceType();
    sequence.addAll(ctx.conditionalExpression());
    ctx.setType(sequence);

    return null;
  }

  @Override
  public Void visitSequenceAssignment(ShadowParser.SequenceAssignmentContext ctx) {
    visitChildren(ctx);

    ShadowParser.SequenceLeftSideContext left = ctx.sequenceLeftSide();
    SequenceType leftSequence = (SequenceType) left.getType();
    ShadowParser.RightSideContext right = ctx.rightSide();
    Type rightType = right.getType();
    ModifiedType rightElement = right;

    // Check lengths first
    if (rightType instanceof SequenceType
        && leftSequence.size() != ((SequenceType) rightType).size()) {
      addError(
          ctx,
          Error.INVALID_ASSIGNMENT,
          "Right hand side "
              + rightType
              + " cannot be assigned to left hand side "
              + leftSequence
              + " because their lengths do not match");
      return null;
    }

    for (int i = 0; i < leftSequence.size(); ++i) {
      ModifiedType leftElement = leftSequence.get(i);
      if (leftElement != null) { // can be skipped
        if (rightType instanceof SequenceType) rightElement = ((SequenceType) rightType).get(i);

        if (leftElement.getType().equals(Type.VAR)) {
          Type type = resolveType(rightElement).getType();
          if (leftElement.getModifiers().isNullable() && type instanceof ArrayType arrayType)
            type = arrayType.convertToNullable();

          if (type.equals(Type.NULL)) {
            type = Type.UNKNOWN;
            addError(
                    ctx,
                    Error.UNDEFINED_TYPE,
                    "Variable declared with var cannot be initialized to null");
          }

          leftElement.setType(type);
        }

        if (leftElement instanceof ShadowParser.SequenceVariableContext) { // Declaration
          if (leftElement.getModifiers().isNullable()
              && leftElement.getType() instanceof ArrayType
              && ((ArrayType) leftElement.getType()).recursivelyGetBaseType().isPrimitive())
            addError(
                ctx,
                Error.INVALID_MODIFIER,
                "Primitive array type " + leftElement.getType() + " cannot be marked nullable");

          addErrors(ctx, isValidInitialization(leftElement, rightElement));
        } else // Otherwise, simple assignment
          addErrors(ctx, isValidAssignment(leftElement, rightElement, AssignmentKind.EQUAL));
      }
    }

    return null;
  }

  @Override
  public Void visitExpression(ShadowParser.ExpressionContext ctx) {
    visitChildren(ctx);

    if (ctx.assignmentOperator() != null) { // if there is assignment
      ShadowParser.PrimaryExpressionContext left = ctx.primaryExpression();
      AssignmentKind kind = AssignmentKind.getKind(ctx.assignmentOperator().getText());
      ShadowParser.ConditionalExpressionContext right = ctx.conditionalExpression();

      List<ShadowException> errors = isValidAssignment(left, right, kind);

      if (errors.isEmpty()) {
        Type leftType = left.getType();
        Type rightType = right.getType();

        if (leftType instanceof PropertyType getSetType) {
          leftType = getSetType.getSetType().getType();
        }

        if (rightType instanceof PropertyType)
          rightType = ((PropertyType) rightType).getGetType().getType();

        // let unbound method type know what signature it will eventually be bound to
        if (rightType instanceof UnboundMethodType unboundType && leftType instanceof MethodReferenceType) {
          MethodType methodType = ((MethodReferenceType) leftType).getMethodType();
          MethodSignature signature =
              unboundType
                  .getOuter()
                  .getMatchingMethod(unboundType.getTypeName(), methodType.getParameterTypes());
          unboundType.setKnownSignature(signature);
        }

        assert kind != null;
        ctx.addOperation(leftType.getMatchingMethod(kind.getMethod(), new SequenceType(rightType)));
      } else addErrors(ctx, errors);
    } else { // did something actually happen?
      if (!ctx.primaryExpression().action)
        addError(ctx, Error.NO_ACTION, "Statement does not perform an action");
    }

    return null;
  }

  @Override
  public Void visitIsExpression(ShadowParser.IsExpressionContext ctx) {
    visitChildren(ctx);

    if (ctx.getChildCount() == 1) {
      Context child = (Context) ctx.getChild(0);
      ctx.setType(child.getType());
      ctx.addModifiers(child.getModifiers());
    } else {
      Type t1 = ctx.relationalExpression().getType();
      Type t2 = ctx.type().getType();

      if (t1.isSubtype(t2) || t2.isSubtype(t1) || t1 instanceof InterfaceType || t2 instanceof InterfaceType)
        ctx.setType(Type.BOOLEAN);
      else {
        addError(
            ctx,
            Error.MISMATCHED_TYPE,
            "Supplied type " + t1 + " cannot be compared with type " + t2 + " in an is statement",
            t1,
            t2);
        ctx.setType(Type.UNKNOWN);
      }

      if (t1 instanceof SingletonType || t2 instanceof SingletonType)
        addError(ctx, Error.MISMATCHED_TYPE, "Cannot use singleton types in an is statement");
      else {
        if (ctx.relationalExpression().getModifiers().isTypeName())
          addError(
              ctx.relationalExpression(),
              Error.NOT_OBJECT,
              "Left hand side of is statement cannot be a type name");

        if (!ctx.type().getModifiers().isTypeName())
          addError(
              ctx.type(), Error.NOT_TYPE, "Right hand side of is statement must be a type name");
      }
    }

    return null;
  }

  @Override
  public Void visitTypeParameters(ShadowParser.TypeParametersContext ctx) {
    // skip class declarations (already done in type updater)
    // also means that TypeParameter will never be visited
    return null;
  }

  @Override
  public Void visitFunctionType(ShadowParser.FunctionTypeContext ctx) {
    visitChildren(ctx);

    MethodType methodType = new MethodType();
    ShadowParser.ResultTypesContext parameters = ctx.resultTypes(0);
    SequenceType parameterTypes = (SequenceType) parameters.getType();

    ShadowParser.ResultTypesContext returns = ctx.resultTypes(1);
    SequenceType returnTypes = (SequenceType) returns.getType();

    for (ModifiedType parameter : parameterTypes) methodType.addParameter(parameter);

    for (ModifiedType type : returnTypes) methodType.addReturn(type);

    ctx.setType(new MethodReferenceType(methodType));
    ctx.addModifiers(Modifiers.TYPE_NAME);

    return null;
  }

  @Override
  public Void visitResultTypes(ShadowParser.ResultTypesContext ctx) {
    visitChildren(ctx);

    SequenceType sequence = new SequenceType();
    sequence.addAll(ctx.resultType());
    ctx.setType(sequence);

    return null;
  }

  @Override
  public Void visitInlineResults(ShadowParser.InlineResultsContext ctx) {
    visitChildren(ctx);

    SequenceType sequence = new SequenceType();
    sequence.addAll(ctx.conditionalExpression());
    ctx.setType(sequence);

    return null;
  }

  @Override
  public Void visitCastExpression(ShadowParser.CastExpressionContext ctx) {
    visitChildren(ctx);

    ShadowParser.TypeContext type = ctx.type();
    ShadowParser.ConditionalExpressionContext expression = ctx.conditionalExpression();
    Type t1 = type.getType(); // type
    Type t2 = expression.getType(); // expression

    ctx.addModifiers(expression.getModifiers());
    ctx.getModifiers().removeModifier(Modifiers.ASSIGNABLE);

    if (ctx.getChild(2).getText().equals("nullable")) {
      if (t1 instanceof ArrayType) {
        t1 = ((ArrayType) t1).convertToNullable();
        ctx.addModifiers(Modifiers.NULLABLE);
      } else
        addError(ctx, Error.INVALID_CAST, "Can only specify nullable for array types in casts");
    }

    // Casting methods
    if (t1 instanceof MethodType method && t2 instanceof UnboundMethodType unboundMethod) {
      MethodSignature candidate = null;
      Type outer = unboundMethod.getOuter();

      for (MethodSignature signature :
          outer.recursivelyGetMethodOverloads(unboundMethod.getTypeName())) {
        MethodType methodType = signature.getMethodType();

        // the list of method signatures starts with the closest (current class) and then adds
        // parents and outer classes
        // always stick with the current if you can
        // (only replace if signature is a subtype of candidate but candidate is not a subtype of
        // signature)
        if (methodType.isSubtype(method)) {
          if (candidate == null
              || (candidate
                  .getMethodType()
                  .isSubtype(
                      methodType))) // take the broadest method possible that matches the cast
            // target
            candidate = signature;
          else if (!methodType.isSubtype(
              candidate
                  .getMethodType())) { // then two acceptable signatures are not subtypes of each
            // other
            addError(
                ctx,
                Error.INVALID_ARGUMENTS,
                "Ambiguous cast from " + unboundMethod.getTypeName() + " to " + method);
            break;
          }
        }
      }

      if (candidate == null)
        addError(
            ctx,
            Error.INVALID_METHOD,
            "No definition of " + unboundMethod.getTypeName() + " matches type " + method);
      else ctx.setType(candidate.getMethodType());
    } else if (t1.isNumerical()
        && t2.isNumerical()) // some numerical types (int and uint) are not superclasses or
      // subclasses of each other
      // for convenience, all numerical types should be castable
      ctx.setType(t1);
    else if (t1.isSubtype(t2) || t2.isSubtype(t1) || t1 instanceof InterfaceType || t2 instanceof  InterfaceType)
      ctx.setType(t1);
    else {
      addError(
          ctx,
          Error.MISMATCHED_TYPE,
          "Supplied type " + t2 + " cannot be cast to type " + t1,
          t1,
          t2);
      ctx.setType(Type.UNKNOWN);
    }

    return null;
  }

  @Override
  public Void visitRightSide(ShadowParser.RightSideContext ctx) {
    visitChildren(ctx);

    Context child = (Context) ctx.getChild(0);
    ctx.setType(child.getType());
    ctx.addModifiers(child.getModifiers());

    return null;
  }

  @Override
  public Void visitSequenceRightSide(ShadowParser.SequenceRightSideContext ctx) {
    visitChildren(ctx);
    SequenceType sequence = new SequenceType();
    sequence.addAll(ctx.conditionalExpression());
    ctx.setType(sequence);

    return null;
  }

  @Override
  public Void visitSequenceVariable(ShadowParser.SequenceVariableContext ctx) {
    visitChildren(ctx);

    if (ctx.type() != null) ctx.setType(ctx.type().getType());
    else ctx.setType(Type.VAR);
    addSymbol(ctx.Identifier().getText(), ctx);

    return null;
  }

  @Override
  public Void visitSequenceLeftSide(ShadowParser.SequenceLeftSideContext ctx) {
    visitChildren(ctx);

    SequenceType sequence = new SequenceType();

    // skip first because it's a parenthesis
    for (int i = 1; i < ctx.getChildCount(); ++i) {
      ParseTree child = ctx.getChild(i);
      if (child instanceof Context) {
        sequence.add((Context) child);
        ++i; // to skip the next comma
      } else sequence.add(null);
    }

    ctx.setType(sequence);

    return null;
  }

  @Override
  public Void visitIfStatement(ShadowParser.IfStatementContext ctx) {
    visitChildren(ctx);

    Type type = ctx.conditionalExpression().getType();

    if (!type.equals(Type.BOOLEAN))
      addError(
          ctx.conditionalExpression(),
          Error.INVALID_TYPE,
          "Condition of if statement cannot accept non-boolean type " + type,
          type);

    return null;
  }

  @Override
  public Void visitWhileStatement(ShadowParser.WhileStatementContext ctx) {
    visitChildren(ctx);

    Type type = ctx.conditionalExpression().getType();

    if (!type.equals(Type.BOOLEAN))
      addError(
          ctx.conditionalExpression(),
          Error.INVALID_TYPE,
          "Condition of while statement cannot accept non-boolean type " + type,
          type);

    return null;
  }

  @Override
  public Void visitDoStatement(ShadowParser.DoStatementContext ctx) {
    visitChildren(ctx);

    Type type = ctx.conditionalExpression().getType();

    if (!type.equals(Type.BOOLEAN))
      addError(
          ctx.conditionalExpression(),
          Error.INVALID_TYPE,
          "Condition of do statement cannot accept non-boolean type " + type,
          type);

    return null;
  }

  @Override
  public Void visitForeachStatement(ShadowParser.ForeachStatementContext ctx) {
    openScope();
    visitChildren(ctx);
    closeScope();

    return null;
  }

  @Override
  public Void visitForeachInit(ShadowParser.ForeachInitContext ctx) {
    visitChildren(ctx);

    Type collectionType = ctx.conditionalExpression().getType();
    ModifiedType element = null;
    boolean isVar = false;
    boolean iterable = true;

    if (ctx.type() != null) ctx.setType(ctx.type().getType());
    else { // var type
      ctx.setType(Type.UNKNOWN);
      isVar = true;
    }

    if (collectionType instanceof ArrayType array) {
      element = new SimpleModifiedType(array.getBaseType());
      if (array.isNullable()) element.getModifiers().addModifier(Modifiers.NULLABLE);
    } else if (collectionType.hasUninstantiatedInterface(Type.CAN_ITERATE)) {
      for (InterfaceType _interface : collectionType.getAllInterfaces())
        if (_interface.getTypeWithoutTypeArguments().equals(Type.CAN_ITERATE)) {
          element = _interface.getTypeParameters().get(0);
          break;
        }
    } else if (collectionType.hasUninstantiatedInterface(Type.CAN_ITERATE_NULLABLE)) {
      for (InterfaceType _interface : collectionType.getAllInterfaces())
        if (_interface.getTypeWithoutTypeArguments().equals(Type.CAN_ITERATE_NULLABLE)) {
          element = _interface.getTypeParameters().get(0);
          break;
        }
    } else {
      addError(
          ctx.conditionalExpression(),
          Error.INVALID_TYPE,
          "Supplied type "
              + collectionType
              + " does not implement "
              + Type.CAN_ITERATE
              + " or "
              + Type.CAN_ITERATE_NULLABLE
              + " and cannot be the target of a foreach statement",
          collectionType);
      iterable = false;
    }

    if (iterable) {
      if (isVar && element != null && element.getType() != null) {
        Type elementType = element.getType();
        ctx.setType(elementType);
        if (ctx.getModifiers().isNullable()) {
          if (elementType instanceof ArrayType arrayType) {
            if (arrayType.recursivelyGetBaseType().isPrimitive())
              addError(
                  ctx,
                  Error.INVALID_MODIFIER,
                  "Primitive array type " + elementType + " cannot be marked nullable");
          }
        }
      }

      List<ShadowException> errors = isValidInitialization(ctx, element);
      if (errors.isEmpty()) addSymbol(ctx.Identifier().getText(), ctx);
      else addErrors(ctx, errors);
    }

    return null;
  }

  @Override
  public Void visitForStatement(ShadowParser.ForStatementContext ctx) {
    openScope();
    visitChildren(ctx);
    closeScope();

    Type type = ctx.conditionalExpression().getType();

    if (!type.equals(Type.BOOLEAN))
      addError(
          ctx.conditionalExpression(),
          Error.INVALID_TYPE,
          "Condition of for statement cannot accept non-boolean type " + type,
          type);

    return null;
  }

  @Override
  public Void visitThrowStatement(ShadowParser.ThrowStatementContext ctx) {
    visitChildren(ctx);

    Type type = ctx.conditionalExpression().getType();

    if (!(type instanceof ExceptionType))
      addError(
          ctx.conditionalExpression(),
          Error.INVALID_TYPE,
          "Supplied type "
              + type
              + " cannot be used in a throw clause, exception or error type required",
          type);

    return null;
  }

  @Override
  public Void visitCatchStatements(ShadowParser.CatchStatementsContext ctx) {
    visitChildren(ctx);

    List<Type> types = new ArrayList<>();

    for (ShadowParser.CatchStatementContext child : ctx.catchStatement()) {
      Type type = child.getType();

      for (Type catchParameter : types)
        if (type.isSubtype(catchParameter)) {
          addError(
              child,
              Error.UNREACHABLE_CODE,
              "Catch block for exception " + type + " cannot be reached",
              type);
          break;
        }

      types.add(type);
    }

    return null;
  }

  @Override
  public Void visitCatchStatement(ShadowParser.CatchStatementContext ctx) {
    openScope(); // For catch parameter
    visitChildren(ctx);
    closeScope();

    ShadowParser.FormalParameterContext child = ctx.formalParameter();
    Type type = child.getType();

    if (!(type instanceof ExceptionType)) {
      addError(
          child,
          Error.INVALID_TYPE,
          "Supplied type "
              + type
              + " cannot be used as a catch parameter, exception types required",
          type);
      ctx.setType(Type.UNKNOWN);
    } else ctx.setType(type);

    if (child.getModifiers().getModifiers() != 0)
      addError(child, Error.INVALID_MODIFIER, "Modifiers cannot be applied to a catch parameter");

    return null;
  }

  @Override
  public Void visitCopyExpression(ShadowParser.CopyExpressionContext ctx) {
    visitChildren(ctx);

    ModifiedType child = ctx.conditionalExpression();
    child = resolveType(child);
    ctx.addModifiers(child.getModifiers());
    if (ctx.getChild(0).getText().equals("freeze")) ctx.addModifiers(Modifiers.IMMUTABLE);
    ctx.setType(child.getType());

    return null;
  }

  @Override
  public Void visitCheckExpression(ShadowParser.CheckExpressionContext ctx) {
    visitChildren(ctx);

    ModifiedType child = ctx.conditionalExpression();
    child = resolveType(child);
    Type type = child.getType();

    if (child.getModifiers().isNullable()) {
      ctx.addModifiers(child.getModifiers());
      ctx.getModifiers().removeModifier(Modifiers.NULLABLE);
    } else
      addError(
          ctx.conditionalExpression(),
          Error.INVALID_TYPE,
          "Non-nullable expression cannot be used in a check statement");

    ctx.setType(type);

    if (isFoundFinally(ctx))
      addError(
          ctx,
          Error.INVALID_STRUCTURE,
          "check statement cannot branch out of a finally block to a recover");

    return null;
  }

  private static boolean isFoundFinally(CheckExpressionContext ctx) {
    ParserRuleContext parent = ctx.getParent();
    boolean foundRecover = false;
    boolean foundFinally = false;

    /*
    catchStatements
    : tryStatement
    catchStatement*
    ('recover' block )?
    ;
     */

    while (parent != null && !foundRecover) {
      // Trying to check out of a finally
      if (parent instanceof BlockContext
          && parent.getParent() instanceof FinallyStatementContext)
        foundFinally = true;

      // Legitimate recover first (we're inside of a try that has a matching recover)
      if (parent instanceof TryStatementContext) {
        CatchStatementsContext grandparent =
            (CatchStatementsContext) parent.getParent();
        if (grandparent.block() != null) foundRecover = true;
      }

      parent = parent.getParent();
    }
    return foundFinally;
  }

  @Override
  public Void visitPrimaryExpression(ShadowParser.PrimaryExpressionContext ctx) {
    curPrefix.addFirst(null);
    visitChildren(ctx);

    if (!ctx.primarySuffix().isEmpty()) { // has suffixes, pull type from last suffix
      ShadowParser.PrimarySuffixContext last = ctx.primarySuffix(ctx.primarySuffix().size() - 1);

      // this primary expression is the left side of an assignment
      if (ctx.getParent() instanceof ShadowParser.ExpressionContext
          || ctx.getParent() instanceof ShadowParser.SequenceLeftSideContext) {
        Type type = last.getType(); // if PropertyType, preserve that
        ctx.addModifiers(last.getModifiers());
        ctx.setType(type);
      } else {
        ModifiedType modifiedType = resolveType(last); // otherwise, strip away the property
        ctx.addModifiers(modifiedType.getModifiers());
        ctx.setType(modifiedType.getType());
      }
    } else { // just prefix
      ShadowParser.PrimaryPrefixContext child = ctx.primaryPrefix();
      ctx.setType(child.getType());
      ctx.addModifiers(child.getModifiers());
    }

    curPrefix.removeFirst(); // pop prefix type off stack

    return null;
  }

  private void checkForSingleton(Type type) {
    // if singleton, add to current method for initialization
    if (type instanceof SingletonType singletonType) {
      if (currentMethod.isEmpty()) {
        // don't add a singleton to itself, could cause infinite recursion
        if (!currentType.equals(type))
          // add to all creates (since it must be declared outside a method)
          for (MethodSignature signature : currentType.recursivelyGetMethodOverloads("create"))
            signature.addSingleton(singletonType);
      } else {
        // don't add a singleton to itself, could cause infinite recursion
        if (!currentType.equals(type)) {
          int i = 0;
          Context signatureNode;
          // find outer method, instead of adding to local or inline methods
          do {
            signatureNode = currentMethod.get(i);
            i++;
          } while (signatureNode instanceof ShadowParser.InlineMethodDefinitionContext
              || signatureNode instanceof ShadowParser.LocalMethodDeclarationContext);

          signatureNode.getSignature().addSingleton(singletonType);
        }
      }
    } else if (type instanceof SequenceType sequenceType) {
      for (ModifiedType modifiedType : sequenceType) checkForSingleton(modifiedType.getType());
    } else if (type instanceof ArrayType) checkForSingleton(((ArrayType) type).getBaseType());
  }

  @Override
  public Void visitPrimaryPrefix(ShadowParser.PrimaryPrefixContext ctx) {
    visitChildren(ctx);

    String image = ctx.getText();

    if (ctx.spawnExpression() != null || ctx.checkExpression() != null || ctx.receiveExpression() != null) {
      ShadowParser.PrimaryExpressionContext parent = (PrimaryExpressionContext) ctx.getParent();
      parent.action = true;
    }

    if (image.equals("this") || image.equals("super")) {
      if (currentType instanceof InterfaceType) {
        addError(
            ctx, Error.INVALID_SELF_REFERENCE, "Reference " + image + " invalid for interfaces");
        ctx.setType(Type.UNKNOWN);
      } else {
        if (image.equals("this")) ctx.setType(currentType);
        else ctx.setType(((ClassType) currentType).getExtendType());

        if (currentType.getModifiers().isImmutable()) ctx.addModifiers(Modifiers.IMMUTABLE);
        else if (currentType.getModifiers().isReadonly()) ctx.addModifiers(Modifiers.READONLY);

        Modifiers methodModifiers = Modifiers.NO_MODIFIERS;
        if (!currentMethod.isEmpty()) methodModifiers = currentMethod.getFirst().getModifiers();

        // Upgrade if current method is non-mutable
        if (methodModifiers.isImmutable() || methodModifiers.isReadonly())
          ctx.getModifiers().upgradeToTemporaryReadonly();
      }
    } else if (ctx.generalIdentifier() != null) {
      if (!setTypeFromName(ctx, image)) {
        addError(ctx, Error.UNDEFINED_SYMBOL, "Symbol " + image + " not defined in this context");
        ctx.setType(Type.UNKNOWN);
      }
    } else if (ctx.conditionalExpression() != null) {
      ctx.setType(ctx.conditionalExpression().getType());
      ctx.addModifiers(ctx.conditionalExpression().getModifiers());
    }
    // literal, check expression, copy expression, cast expression, spawn expression
    // primitive and function types, and array initializer
    else {
      Context child = (Context) ctx.getChild(0);
      ctx.setType(child.getType());
      ctx.addModifiers(child.getModifiers());
    }

    checkForSingleton(ctx.getType());
    curPrefix.set(0, ctx); // so that the suffix can figure out where it's at

    return null;
  }

  @Override
  public Void visitTypeArguments(ShadowParser.TypeArgumentsContext ctx) {
    visitChildren(ctx);

    SequenceType sequence = new SequenceType();
    sequence.addAll(ctx.type());
    ctx.setType(sequence);

    return null;
  }

  @Override
  public Void visitPrimarySuffix(ShadowParser.PrimarySuffixContext ctx) {
    visitChildren(ctx);

    Context prefixNode = curPrefix.getFirst();

    if (prefixNode.getModifiers().isNullable() && !(prefixNode.getType() instanceof ArrayType))
      addError(prefixNode, Error.INVALID_DEREFERENCE, "Nullable reference cannot be dereferenced");

    if (ctx.property() != null || ctx.methodCall() != null || ctx.allocation() != null) {
      ShadowParser.PrimaryExpressionContext parent = (PrimaryExpressionContext) ctx.getParent();
      parent.action = true;
    }

    if (ctx.methodCall() != null) {
      Type childType = ctx.methodCall().getType();

      if (childType instanceof MethodType methodType) {
        SequenceType returnTypes = methodType.getReturnTypes();
        returnTypes.setContextType(ctx); // used instead of setType
      } else ctx.setType(Type.UNKNOWN);
    } else {
      Context child = (Context) ctx.getChild(0);
      ctx.setType(child.getType());
      ctx.addModifiers(child.getModifiers());
    }

    checkForSingleton(ctx.getType());
    curPrefix.set(0, ctx); // so that a future suffix can figure out where it's at

    return null;
  }

  @Override
  public Void visitBrackets(ShadowParser.BracketsContext ctx) {
    visitChildren(ctx);

    ModifiedType prefixNode = curPrefix.getFirst();
    prefixNode = resolveType(prefixNode);
    Type prefixType = prefixNode.getType();

    if (prefixNode.getModifiers().isTypeName() && !(prefixType instanceof ArrayType)) {
      ctx.setType(new ArrayType(prefixType, getDimensions(ctx), false));
      ctx.addModifiers(Modifiers.TYPE_NAME);
    } else {
      addError(curPrefix.getFirst(), Error.NOT_TYPE, "Can only apply brackets to a type name");
      ctx.setType(Type.UNKNOWN);
    }

    return null;
  }

  @Override
  public Void visitSubscript(ShadowParser.SubscriptContext ctx) {
    visitChildren(ctx);

    ModifiedType prefixNode = curPrefix.getFirst();
    prefixNode = resolveType(prefixNode);
    Type prefixType = prefixNode.getType();

    if (prefixType instanceof ArrayType arrayType
        && !(((ArrayType) prefixType).getBaseType() instanceof TypeParameter)) {

      ShadowParser.ConditionalExpressionContext child = ctx.conditionalExpression();
      Type childType = child.getType();

      if (!childType.isSubtype(Type.LONG)) {
        addError(
            child,
            Error.INVALID_SUBSCRIPT,
            "Subscript type " + childType + " is invalid, must be subtype of " + Type.LONG,
            childType);
        ctx.setType(Type.UNKNOWN);
        return null;
      }

      ctx.setType(arrayType.getBaseType());
      if (prefixNode.getModifiers().isImmutable()) ctx.addModifiers(Modifiers.IMMUTABLE);
      else if (prefixNode.getModifiers().isReadonly()) ctx.addModifiers(Modifiers.READONLY);
      else if (prefixNode.getModifiers().isTemporaryReadonly())
        ctx.addModifiers(Modifiers.TEMPORARY_READONLY);
      else ctx.addModifiers(Modifiers.ASSIGNABLE);

      // backdoor for creates
      // immutable and readonly array should only be assignable in creates
      if (prefixNode.getModifiers().isAssignable()) ctx.addModifiers(Modifiers.ASSIGNABLE);

      // primitive arrays are initialized to default values
      // non-primitive array elements could be null
      // however, arrays of arrays are not-null
      // instead, they will be filled with default values, including a length of zero
      // thus, we don't need to check nullability, but we do need a range check

      // nullable array means what you get out is nullable, not the array itself
      if (arrayType.isNullable()) ctx.addModifiers(Modifiers.NULLABLE);
    } else if (prefixType.hasUninstantiatedInterface(Type.CAN_INDEX)
        || prefixType.hasUninstantiatedInterface(Type.CAN_INDEX_NULLABLE)
        || prefixType.hasUninstantiatedInterface(Type.CAN_INDEX_STORE)
        || prefixType.hasUninstantiatedInterface(Type.CAN_INDEX_STORE_NULLABLE)) {

      SequenceType arguments = new SequenceType();
      ShadowParser.ConditionalExpressionContext child = ctx.conditionalExpression();
      arguments.add(child);

      MethodSignature signature = setMethodType(ctx, prefixType, "index", arguments);

      if (signature != null
          && (prefixNode.getModifiers().isReadonly()
              || prefixNode.getModifiers().isTemporaryReadonly()
              || prefixNode.getModifiers().isImmutable())
          && signature.getModifiers().isMutable()) {
        ctx.setType(Type.UNKNOWN);
        addError(
            ctx,
            Error.INVALID_SUBSCRIPT,
            "Cannot apply mutable subscript to immutable or readonly prefix");
      } else {
        // if signature is null, then it is not a load
        SubscriptType subscriptType =
            new SubscriptType(
                signature,
                child,
                new UnboundMethodType("index", prefixType),
                prefixNode,
                currentType);
        ctx.setType(subscriptType);
        if (signature != null) ctx.addModifiers(subscriptType.getGetType().getModifiers());

        if (prefixNode.getModifiers().isImmutable()) ctx.addModifiers(Modifiers.IMMUTABLE);
        else if (prefixNode.getModifiers().isReadonly()) ctx.addModifiers(Modifiers.READONLY);
        else if (prefixNode.getModifiers().isTemporaryReadonly())
          ctx.addModifiers(Modifiers.TEMPORARY_READONLY);
        else if (prefixType.hasUninstantiatedInterface(Type.CAN_INDEX_STORE)
            || prefixType.hasUninstantiatedInterface(Type.CAN_INDEX_STORE_NULLABLE))
          ctx.addModifiers(Modifiers.ASSIGNABLE);
      }
    } else {
      ctx.setType(Type.UNKNOWN);
      addError(
          ctx,
          Error.INVALID_SUBSCRIPT,
          "Subscript is not permitted for type "
              + prefixType
              + " because it does not implement "
              + Type.CAN_INDEX
              + ", "
              + Type.CAN_INDEX_STORE
              + ", "
              + Type.CAN_INDEX_NULLABLE
              + ", or "
              + Type.CAN_INDEX_STORE_NULLABLE,
          prefixType);
    }

    return null;
  }

  @Override
  public Void visitDestroy(ShadowParser.DestroyContext ctx) {
    visitChildren(ctx);

    Context prefixNode = curPrefix.getFirst();
    Type prefixType = prefixNode.getType();

    if (prefixNode.getModifiers().isTypeName())
      addError(prefixNode, Error.INVALID_DESTROY, "Type name cannot be destroyed");
    else if (prefixType instanceof UnboundMethodType)
      addError(prefixNode, Error.INVALID_DESTROY, "Method cannot be destroyed");
    else if (prefixType instanceof PropertyType)
      addError(prefixNode, Error.INVALID_DESTROY, "Property cannot be destroyed");

    ctx.setType(Type.UNKNOWN); // Destruction has no type
    // TODO: make sure an error is reported if someone tries to do something to a destroy

    return null;
  }

  @Override
  public Void visitArrayCreate(ShadowParser.ArrayCreateContext ctx) {
    visitChildren(ctx);

    Context prefixNode = curPrefix.getFirst();
    Type prefixType = prefixNode.getType();
    SequenceType typeArguments = null;
    int nullOrCreateLocation = 1;

    ctx.prefixType = prefixType;

    boolean nullable = false;

    if (ctx.typeArguments() != null) {
      typeArguments = (SequenceType) ctx.typeArguments().getType();
      nullOrCreateLocation++;
    }

    ShadowParser.ArrayDimensionsContext dimensions = ctx.arrayDimensions();

    if (ctx.getChild(nullOrCreateLocation).getText().equals("null")) {
      nullable = true;
      ctx.addModifiers(Modifiers.NULLABLE);

      if (prefixType instanceof ArrayType arrayType) {
        prefixType = arrayType.convertToNullable();
      }
    }

    // create array
    if (prefixNode.getModifiers().isTypeName()) {
      ArrayType arrayType = null;
      if (typeArguments != null) {
        if (prefixType.isParameterized()) {
          if (prefixType
              .getTypeParameters()
              .canAccept(typeArguments, SubstitutionKind.TYPE_PARAMETER)) {
            try {
              prefixType = prefixType.replace(prefixType.getTypeParameters(), typeArguments);
              arrayType = new ArrayType(prefixType, getDimensions(dimensions), nullable);
              ctx.setType(arrayType);
            } catch (InstantiationException e) {
              addError(
                  ctx.typeArguments(),
                  Error.INVALID_TYPE_ARGUMENTS,
                  "Supplied type arguments "
                      + typeArguments
                      + " do not match type parameters "
                      + prefixType.getTypeParameters());
              ctx.setType(Type.UNKNOWN);
            }
          } else {
            addError(
                ctx.typeArguments(),
                Error.INVALID_TYPE_ARGUMENTS,
                "Supplied type arguments "
                    + typeArguments
                    + " do not match type parameters "
                    + prefixType.getTypeParameters());
            ctx.setType(Type.UNKNOWN);
          }
        } else {
          addError(
              ctx.typeArguments(),
              Error.UNNECESSARY_TYPE_ARGUMENTS,
              "Type arguments "
                  + typeArguments
                  + " supplied for non-parameterized type "
                  + prefixType,
              prefixType);
          ctx.setType(Type.UNKNOWN);
        }
      } else {
        if (!prefixType.isParameterized()) {
          arrayType = new ArrayType(prefixType, getDimensions(dimensions), nullable);
          ctx.setType(arrayType);
        } else {
          addError(
              ctx,
              Error.MISSING_TYPE_ARGUMENTS,
              "Type arguments not supplied for paramterized type " + prefixType);
          ctx.setType(Type.UNKNOWN);
        }
      }

      currentType.addUsedType(arrayType);
      currentType.addPartiallyInstantiatedClass(arrayType);

      if (ctx.arrayCreateCall() != null) {
        SequenceType arguments = (SequenceType) ctx.arrayCreateCall().getType();

        MethodSignature signature =
            prefixType.getMatchingMethod("create", arguments, typeArguments);
        if (signature == null || !methodIsAccessible(signature, currentType))
          addError(
              ctx,
              Error.INVALID_CREATE,
              "Cannot create array of type " + prefixType + " with specified create arguments",
              prefixType);
        else if (prefixType instanceof InterfaceType)
          addError(
              ctx,
              Error.INVALID_CREATE,
              "Cannot create non-nullable array of interface type " + prefixType,
              prefixType);
        else ctx.setSignature(signature);
      } else if (ctx.arrayDefault() != null) {
        ShadowParser.ArrayDefaultContext child = ctx.arrayDefault();
        assert arrayType != null;
        Type baseType = arrayType.getBaseType();
        if (child.getType().isSubtype(baseType)) {
          if (child.getModifiers().isNullable() && !ctx.getModifiers().isNullable())
            addError(
                child,
                Error.INVALID_MODIFIER,
                "Cannot apply nullable default value to non-nullable array",
                child.getType(),
                baseType);
        } else
          addError(
              ctx.arrayDefault(),
              Error.INVALID_TYPE,
              "Cannot apply type "
                  + child.getType()
                  + " as default value in array of type "
                  + arrayType,
              child.getType());
      } else if (!nullable && !prefixType.isPrimitive() && !(prefixType instanceof ArrayType)) {
        // try default constructor
        MethodSignature signature =
            prefixType.getMatchingMethod("create", new SequenceType(), typeArguments);
        if (signature == null || !methodIsAccessible(signature, currentType))
          addError(
              ctx,
              Error.INVALID_CREATE,
              "Cannot create array of type "
                  + prefixType
                  + " which does not implement a default create",
              prefixType);
        else if (prefixType instanceof InterfaceType)
          addError(
              ctx,
              Error.INVALID_CREATE,
              "Cannot create non-nullable array of interface type " + prefixType,
              prefixType);
        else ctx.setSignature(signature);
      }
    } else {
      addError(prefixNode, Error.NOT_TYPE, "Type name must be used to create an array");
      ctx.setType(Type.UNKNOWN);
    }

    return null;
  }

  @Override
  public Void visitArrayCreateCall(ShadowParser.ArrayCreateCallContext ctx) {
    visitChildren(ctx);

    SequenceType sequence = new SequenceType();
    sequence.addAll(ctx.conditionalExpression());
    ctx.setType(sequence);

    return null;
  }

  @Override
  public Void visitArrayDefault(ShadowParser.ArrayDefaultContext ctx) {
    visitChildren(ctx);

    ctx.setType(ctx.conditionalExpression().getType());
    ctx.addModifiers(ctx.conditionalExpression().getModifiers());

    return null;
  }

  @Override
  public Void visitMethod(ShadowParser.MethodContext ctx) {
    visitChildren(ctx);

    // Always part of a suffix, thus always has a prefix
    ModifiedType prefixNode = curPrefix.getFirst();
    prefixNode = resolveType(prefixNode);
    Type prefixType = prefixNode.getType();
    String methodName = ctx.Identifier().getText();

    if (prefixNode.getModifiers().isTypeName() ) {
      if (prefixType instanceof AttributeType attributeType) {
        MethodSignature signature = currentMethod.getFirst().getSignature();
        if (signature.getAttributeInvocation(attributeType) == null)
          addError(curPrefix.getFirst(), Error.MISMATCHED_TYPE, "Attribute type " + attributeType + " has not been invoked for this method");

        PrimarySuffixContext parent = (PrimarySuffixContext) ctx.getParent();
        List<PrimarySuffixContext> suffixes =
                ((PrimaryExpressionContext) parent.getParent()).primarySuffix();
        int index = 0;
        while (suffixes.get(index) != parent) index++;
        index++;

        // If there are no more suffixes after this one or the next suffix is not a method call,
        // this is a method reference
        if (index == suffixes.size() || suffixes.get(index).methodCall() == null)
          addError(ctx, Error.INVALID_STRUCTURE, "A method reference cannot be used from an attribute");

      }
      else if (!(prefixType instanceof SingletonType))
        addError(curPrefix.getFirst(), Error.NOT_OBJECT, "Type name cannot be used to call method");
    }

    if (prefixType != null) {
      if (prefixType instanceof SequenceType) {
        addError(
                curPrefix.getFirst(), Error.INVALID_TYPE, "Method cannot be called on a sequence result");
      }
      else {
        List<MethodSignature> methods = prefixType.recursivelyGetMethodOverloads(methodName);

        // unbound method (it gets bound when you supply arguments)
        if (methods != null && !methods.isEmpty())
          ctx.setType(new UnboundMethodType(methodName, prefixType));
        else
          addError(
              ctx, Error.UNDEFINED_SYMBOL, "Method " + methodName + " not defined in this context");
      }
    }

    if (ctx.getType() == null) ctx.setType(Type.UNKNOWN);

    // these push the immutable or readonly modifier to the prefix of the call
    if (prefixNode.getModifiers().isImmutable()) ctx.addModifiers(Modifiers.IMMUTABLE);
    else if (prefixNode.getModifiers().isReadonly()) ctx.addModifiers(Modifiers.READONLY);
    else if (prefixNode.getModifiers().isTemporaryReadonly())
      ctx.addModifiers(Modifiers.TEMPORARY_READONLY);

    return null;
  }

  @Override
  public Void visitAllocation(ShadowParser.AllocationContext ctx) {
    visitChildren(ctx);

    Context child = (Context) ctx.getChild(0);
    ctx.addModifiers(child.getModifiers());

    // only get type from arrayCreate
    // create sets differently
    if (ctx.arrayCreate() != null) ctx.setType(child.getType());

    return null;
  }

  @Override
  public Void visitCreate(ShadowParser.CreateContext ctx) {
    visitChildren(ctx);

    Context prefixNode = curPrefix.getFirst();
    Type prefixType = prefixNode.getType();
    ctx.setType(Type.UNKNOWN);
    Context parent = (Context) ctx.getParent();

    if (prefixType instanceof InterfaceType)
      addError(curPrefix.getFirst(), Error.INVALID_CREATE, "Interfaces cannot be created");
    else if (prefixType instanceof SingletonType)
      addError(curPrefix.getFirst(), Error.INVALID_CREATE, "Singletons cannot be created");
    else if (prefixType instanceof AttributeType)
      addError(curPrefix.getFirst(), Error.INVALID_CREATE, "Attributes cannot be created with an explicit call");
    else if (prefixType instanceof EnumType)
      //TODO: Add error check for this
      addError(curPrefix.getFirst(), Error.INVALID_CREATE, "Enums cannot be created with an explicit call");
    else if (!(prefixType instanceof ClassType)
        && !(prefixType instanceof TypeParameter
            && !prefixType.recursivelyGetMethodOverloads("create").isEmpty()))
      addError(
          curPrefix.getFirst(),
          Error.INVALID_CREATE,
          "Type " + prefixType + " cannot be created",
          prefixType);
    else if (!prefixNode.getModifiers().isTypeName())
      addError(curPrefix.getFirst(), Error.INVALID_CREATE, "Only a type can be created");
    else {
      SequenceType typeArguments = null;
      if (ctx.typeArguments() != null) {
        typeArguments = (SequenceType) ctx.typeArguments().getType();
      }

      SequenceType arguments = new SequenceType();
      arguments.addAll(ctx.conditionalExpression());

      MethodSignature signature;

      if (typeArguments != null) {
        if (prefixType.isParameterized()
            && prefixType
                .getTypeParameters()
                .canAccept(typeArguments, SubstitutionKind.TYPE_PARAMETER)) {
          try {
            prefixType = prefixType.replace(prefixType.getTypeParameters(), typeArguments);
            signature = setCreateType(ctx, prefixType, arguments);
            ctx.setSignature(signature);
            parent.setType(prefixType);

            // Occasionally, this type is only seen here with all parameters
            // and must be added to the referenced types.
            // The raw type has already been added
            currentType.addUsedType(prefixType);
            currentType.addPartiallyInstantiatedClass(prefixType);
          } catch (InstantiationException e) {
            addError(
                ctx.typeArguments(),
                Error.INVALID_TYPE_ARGUMENTS,
                "Supplied type arguments "
                    + typeArguments
                    + " do match type parameters "
                    + prefixType.getTypeParameters());
          }
        } else
          addError(
              ctx.typeArguments(),
              Error.INVALID_TYPE_ARGUMENTS,
              "Supplied type arguments "
                  + typeArguments
                  + " do match type parameters "
                  + prefixType.getTypeParameters());
      } else {
        if (!prefixType.isParameterized()) {
          signature = setCreateType(ctx, prefixType, arguments);
          ctx.setSignature(signature);
          parent.setType(prefixType);
        } else
          addError(
              ctx,
              Error.MISSING_TYPE_ARGUMENTS,
              "Type arguments not supplied for parameterized type " + prefixType);
      }
    }

    if (ctx.getType() == Type.UNKNOWN) parent.setType(Type.UNKNOWN);

    return null;
  }

  @Override
  public Void visitClassSpecifier(ShadowParser.ClassSpecifierContext ctx) {
    visitChildren(ctx);

    // always part of a suffix, thus always has a prefix
    ModifiedType prefixNode = curPrefix.getFirst();
    boolean isTypeName = prefixNode.getModifiers().isTypeName();
    Type prefixType = prefixNode.getType();

    if (isTypeName) {
      if (ctx.typeArguments() != null) { // has type arguments
        ShadowParser.TypeArgumentsContext arguments = ctx.typeArguments();
        SequenceType parameterTypes = prefixType.getTypeParameters();
        SequenceType argumentTypes = (SequenceType) arguments.getType();

        if (!prefixType.isParameterized())
          addError(
              ctx.typeArguments(),
              Error.UNNECESSARY_TYPE_ARGUMENTS,
              "Type arguments "
                  + argumentTypes
                  + " supplied for non-parameterized type "
                  + prefixType,
              prefixType);
        else if (!parameterTypes.canAccept(argumentTypes, SubstitutionKind.TYPE_PARAMETER))
          addError(
              ctx.typeArguments(),
              Error.INVALID_TYPE_ARGUMENTS,
              "Supplied type arguments "
                  + argumentTypes
                  + " do not match type parameters "
                  + parameterTypes);
        else {
          try {
            Type replacedType = prefixType.replace(parameterTypes, argumentTypes);

            currentType.addUsedType(replacedType);
            currentType.addPartiallyInstantiatedClass(replacedType);
          } catch (InstantiationException e) {
            addError(
                ctx.typeArguments(),
                Error.INVALID_TYPE_ARGUMENTS,
                "Supplied type arguments "
                    + argumentTypes
                    + " do not match type parameters "
                    + parameterTypes);
          }
        }
      } else if (prefixType.isParameterized())
        addError(
            ctx,
            Error.MISSING_TYPE_ARGUMENTS,
            "Type arguments not supplied for parameterized type " + prefixType);
    } else addError(ctx, Error.NOT_TYPE, "class specifier requires type name for access");

    ctx.setType(Type.CLASS);
    ctx.addModifiers(Modifiers.IMMUTABLE);

    return null;
  }

  @Override
  public Void visitScopeSpecifier(ShadowParser.ScopeSpecifierContext ctx) {
    visitChildren(ctx);

    // always part of a suffix, thus always has a prefix
    ModifiedType prefixNode = curPrefix.getFirst();
    prefixNode = resolveType(prefixNode);
    Type prefixType = prefixNode.getType();
    boolean isTypeName = prefixNode.getModifiers().isTypeName();
    String name = ctx.Identifier().getText();

    if (prefixType.containsField(name)) {
      Context field = prefixType.getField(name);

      if (!fieldIsAccessible(field, currentType)) {
        addError(ctx, Error.ILLEGAL_ACCESS, "Field " + name + " not accessible from this context");
      } else if (!(field.getModifiers().isConstant() || prefixType instanceof AttributeType) && isTypeName) {
        addError(
            ctx,
            Error.ILLEGAL_ACCESS,
            "Field " + name + " is only accessible from an object reference");
      } else {
        ctx.setType(field.getType());
        ctx.addModifiers(field.getModifiers());

        if (!prefixNode.getModifiers().isMutable()) ctx.getModifiers().upgradeToTemporaryReadonly();

        // Only in a create if
        // prefix is "this"
        // current method is create
        // current method is inside prefix type
        boolean insideCreate;

        if (currentMethod.isEmpty()) insideCreate = true; // Declaration
        else {
          MethodSignature signature = currentMethod.getFirst().getSignature();
          insideCreate =
              curPrefix.getFirst().getText().equals("this")
                  && signature.isCreate()
                  && signature.getOuter().equals(prefixType);
        }

        if (ctx.getModifiers().isMutable() || insideCreate) ctx.addModifiers(Modifiers.ASSIGNABLE);
      }
    } else if (prefixType.recursivelyContainsConstant(name)) {
      Context constant = prefixType.recursivelyGetConstant(name);

      if (!isTypeName)
        addError(ctx, Error.ILLEGAL_ACCESS, "Constant " + name + " requires type name for access");

      ctx.setType(constant.getType());
      ctx.addModifiers(constant.getModifiers());
    } else if (prefixType.containsInnerType(name)) {
      Type innerType = prefixType.getInnerType(name);

      if (!currentType.canSee(innerType))
        addError(
            ctx,
            Error.ILLEGAL_ACCESS,
            "Type " + innerType + " is not accessible from this context",
            innerType);
      else {
        ctx.setType(innerType);
        ctx.addModifiers(Modifiers.TYPE_NAME);
      }
    } else
      addError(
          ctx,
          Error.UNDEFINED_SYMBOL,
          "Field or inner type " + name + " not defined in this context");

    if (ctx.getType() == null) ctx.setType(Type.UNKNOWN);

    return null;
  }

  @Override
  public Void visitProperty(ShadowParser.PropertyContext ctx) {
    visitChildren(ctx);

    // always part of a suffix, thus always has a prefix
    ModifiedType prefixNode = curPrefix.getFirst();
    prefixNode = resolveType(prefixNode);
    Type prefixType = prefixNode.getType();
    String propertyName = ctx.Identifier().getText();
    boolean isTypeName =
        prefixNode.getModifiers().isTypeName() && !(prefixType instanceof SingletonType);

    if (isTypeName) {
      addError(
          curPrefix.getFirst(),
          Error.NOT_OBJECT,
          "Object reference must be used to access property " + propertyName);
      ctx.setType(Type.UNKNOWN);
    } else {
      List<MethodSignature> methods = prefixType.recursivelyGetMethodOverloads(propertyName);
      if (prefixNode.getModifiers().isImmutable()) ctx.addModifiers(Modifiers.IMMUTABLE);
      else if (prefixNode.getModifiers().isReadonly()) ctx.addModifiers(Modifiers.READONLY);
      else if (prefixNode.getModifiers().isTemporaryReadonly())
        ctx.addModifiers(Modifiers.TEMPORARY_READONLY);

      if (methods != null && !methods.isEmpty()) {
        MethodSignature getter = null;
        UnboundMethodType setterName = null;

        for (MethodSignature signature : methods) {
          if (signature.getModifiers().isGet()) getter = signature;
          else if (setterName == null && signature.getModifiers().isSet())
            setterName = new UnboundMethodType(signature.getSymbol(), signature.getOuter());
        }

        boolean mutableProblem = false;
        boolean accessibleProblem = false;

        if (getter != null && !methodIsAccessible(getter, currentType)) {
          accessibleProblem = true;
          getter = null;
        }

        if (getter != null
            && !prefixNode.getModifiers().isMutable()
            && getter.getModifiers().isMutable()) {
          mutableProblem = true;
          getter = null;
        }

        if (getter != null && getter.getReturnTypes().getModifiers(0).isNullable())
          ctx.addModifiers(Modifiers.NULLABLE);

        if (setterName == null) {
          ctx.setType(Type.UNKNOWN); // assume bad
          if (mutableProblem)
            addError(
                ctx,
                Error.ILLEGAL_ACCESS,
                "Mutable property "
                    + propertyName
                    + " cannot be called from "
                    + (prefixNode.getModifiers().isImmutable() ? "immutable" : "readonly")
                    + " context");
          else if (accessibleProblem)
            addError(
                ctx,
                Error.ILLEGAL_ACCESS,
                "Property " + propertyName + " is not accessible in this context");
          else if (getter == null)
            addError(
                ctx,
                Error.INVALID_PROPERTY,
                "Property " + propertyName + " is not defined in this context");
          else // only case where it works out
          ctx.setType(new PropertyType(getter, null, prefixNode, currentType));
        } else ctx.setType(new PropertyType(getter, setterName, prefixNode, currentType));
      } else {
        addError(
            ctx,
            Error.INVALID_PROPERTY,
            "Property " + propertyName + " not defined in this context");
        ctx.setType(Type.UNKNOWN); // If got here, some error
      }
    }

    return null;
  }

  private ModifiedType resolveType(
      ModifiedType node) { // Dereferences into PropertyType or IndexType for getter, if needed
    Type type = node.getType();
    if (type instanceof PropertyType getSetType) { // includes SubscriptType as well
      if (getSetType.isGettable()) return getSetType.getGetType();
      else {
        String kind = (type instanceof SubscriptType) ? "Subscript " : "Property ";
        if (node instanceof Context)
          addError(
              (Context) node,
              Error.ILLEGAL_ACCESS,
              kind + node + " does not have appropriate get access");
        else
          addError(
              new TypeCheckException(
                  Error.ILLEGAL_ACCESS, kind + node + " does not have appropriate get access"));
        return new SimpleModifiedType(Type.UNKNOWN);
      }
    } else return node;
  }

  protected MethodSignature setCreateType(Context node, Type prefixType, SequenceType arguments) {
    return setMethodType(node, prefixType, "create", arguments);
  }


  protected MethodSignature setMethodType(
      Context node, Type type, String method, SequenceType arguments) {
    List<ShadowException> errors = new ArrayList<>();
    MethodSignature signature = type.getMatchingMethod(method, arguments, errors);

    if (signature == null) addErrors(node, errors);
    else {
      if (!methodIsAccessible(signature, currentType))
        addError(
            node,
            Error.ILLEGAL_ACCESS,
            signature.getSymbol()
                + signature.getMethodType()
                + " is not accessible from this context");

      // if any arguments have an UnboundMethodType, note down what their true MethodSignature must
      // be
      for (int i = 0; i < arguments.size(); ++i) {
        ModifiedType argument = arguments.get(i);
        if (argument.getType() instanceof UnboundMethodType unboundType) {
          // since it matches, the parameter of the method must be a MethodType
          MethodType parameterType = (MethodType) signature.getParameterTypes().get(i).getType();
          MethodSignature boundSignature =
              unboundType
                  .getOuter()
                  .getMatchingMethod(unboundType.getTypeName(), parameterType.getParameterTypes());
          unboundType.setKnownSignature(boundSignature);
        }
      }
      node.setType(signature.getMethodType());
    }

    return signature;
  }

  @Override
  public Void visitMethodCall(ShadowParser.MethodCallContext ctx) {
    visitChildren(ctx);

    // always part of a suffix, thus always has a prefix
    ModifiedType prefixNode = curPrefix.getFirst();
    prefixNode = resolveType(prefixNode);
    Type prefixType = prefixNode.getType();

    SequenceType arguments = new SequenceType();
    arguments.addAll(ctx.conditionalExpression());

    if (prefixType instanceof UnboundMethodType) {
      UnboundMethodType unboundMethod = (UnboundMethodType) (prefixType);
      Type outer = prefixType.getOuter();
      // Sets ctx type inside
      MethodSignature signature = setMethodType(ctx, outer, unboundMethod.getTypeName(), arguments);
      ctx.setSignature(signature);

      if (signature != null) {
        for (ModifiedType modifiedType : signature.getReturnTypes()) {
          currentType.addUsedType(modifiedType.getType());
        }
      }

      if (signature != null
          && prefixNode.getModifiers().isImmutable()
          && signature.getModifiers().isMutable()) {
        addError(
            ctx,
            Error.ILLEGAL_ACCESS,
            "Mutable method " + signature + " cannot be called from an immutable reference");
      }

      if (signature != null
          && (prefixNode.getModifiers().isReadonly()
              || prefixNode.getModifiers().isTemporaryReadonly())
          && signature.getModifiers().isMutable()) {
        addError(
            ctx,
            Error.ILLEGAL_ACCESS,
            "Mutable method " + signature + " cannot be called from a readonly reference");
      }
    } else if (prefixType
        instanceof MethodReferenceType) { // only happens with method pointers and local methods
      MethodType methodType = ((MethodReferenceType) prefixType).getMethodType();

      if (methodType.isInline()) {
        for (Context signatureNode : currentMethod) {
          if (signatureNode.getSignature().getMethodType()
              == methodType) { // if literally the same MethodType, we have illegal recursion
            addError(ctx, Error.INVALID_STRUCTURE, "Inline method cannot be called recursively");
          }
        }
      }

      if (methodType.canAccept(arguments)) ctx.setType(prefixType);
      else {
        addError(
            ctx,
            Error.INVALID_ARGUMENTS,
            "Supplied method arguments "
                + arguments
                + " do not match method parameters "
                + methodType.getParameterTypes(),
            arguments,
            methodType.getParameterTypes());
        ctx.setType(Type.UNKNOWN);
      }

      if (prefixNode.getModifiers().isImmutable()
          && !methodType.getModifiers().isImmutable()
          && !methodType.getModifiers().isReadonly()) {
        addError(
            ctx,
            Error.ILLEGAL_ACCESS,
            "Mutable method cannot be called from an immutable reference");
      }

      if (prefixNode.getModifiers().isReadonly()
          && !methodType.getModifiers().isImmutable()
          && !methodType.getModifiers().isReadonly()) {
        addError(
            ctx, Error.ILLEGAL_ACCESS, "Mutable method cannot be called from a readonly reference");
      }
    } else {
      addError(
          ctx,
          Error.INVALID_METHOD,
          "Cannot apply arguments to non-method type " + prefixType,
          prefixType);
      ctx.setType(Type.UNKNOWN);
    }

    return null;
  }


  @Override
  public Void visitBreakOrContinueStatement(ShadowParser.BreakOrContinueStatementContext ctx) {
    // No children to visit
    ParserRuleContext parent = ctx.getParent();
    boolean foundLoop = false;
    boolean foundFinally = false;
    String name = ctx.getText().substring(0, ctx.getText().length() - 1).trim();

    while (parent != null && !foundLoop) {
      // Trying to break or continue out of a finally
      if (parent instanceof ShadowParser.BlockContext
          && parent.getParent() instanceof ShadowParser.FinallyStatementContext)
        foundFinally = true;

      // Legitimate loop to break out of
      if (parent instanceof ShadowParser.DoStatementContext
          || parent instanceof ShadowParser.ForeachStatementContext
          || parent instanceof ShadowParser.ForStatementContext
          || parent instanceof ShadowParser.WhileStatementContext) foundLoop = true;
      else parent = parent.getParent();
    }

    if (!foundLoop)
      addError(
          ctx, Error.INVALID_STRUCTURE, name + " statement cannot occur outside of a loop body");

    if (foundFinally)
      addError(ctx, Error.INVALID_STRUCTURE, name + " statement cannot exit a finally block");

    return null;
  }

  @Override
  public Void visitArrayInitializer(ShadowParser.ArrayInitializerContext ctx) {
    visitChildren(ctx);

    Type result = null;
    boolean first = true;

    boolean nullable = false;
    List<? extends Context> children;
    if (!ctx.arrayInitializer().isEmpty()) {
      children = ctx.arrayInitializer();
    } else {
      children = ctx.conditionalExpression();
    }

    for (Context child : children) { // cycle through types, upgrading to broadest legal type
      Type type = child.getType();

      if (first) {
        result = type;
        first = false;
      } else {
        if (result.isSubtype(type)) {
          result = type;
        } else if (!type.isSubtype(result)) { // neither is subtype of other, panic!
          addError(ctx, Error.MISMATCHED_TYPE, "Types in array initializer list do not match");
          ctx.setType(Type.UNKNOWN);
          return null;
        }
      }

      if (child.getModifiers().isNullable()) {
        nullable = true;
      }
    }

    // New code assumes that result is array of arrays
    ArrayType arrayType;

    if (nullable) {
      ctx.addModifiers(Modifiers.NULLABLE);
    }
    arrayType = new ArrayType(result, 1, nullable);

    ctx.setType(arrayType);
    currentType.addUsedType(arrayType);
    currentType.addPartiallyInstantiatedClass(arrayType);

    return null;
  }

  @Override
  public Void visitAssertStatement(ShadowParser.AssertStatementContext ctx) {
    visitChildren(ctx);

    Type assertType = ctx.conditionalExpression(0).getType();

    if (!assertType.equals(Type.BOOLEAN)) {
      addError(
          ctx.conditionalExpression(0),
          Error.INVALID_TYPE,
          "Supplied type "
              + assertType
              + " cannot be used in the condition of an assert, boolean required",
          assertType);
    } else if (ctx.conditionalExpression().size() == 2) {
      ShadowParser.ConditionalExpressionContext child = ctx.conditionalExpression(1);
      Type type = child.getType();
      if (child.getModifiers().isTypeName() && !(type instanceof SingletonType)) {
        addError(
            child,
            Error.NOT_OBJECT,
            "Object type required for assert information but type name used");
      }
    }

    return null;
  }

  @Override
  public Void visitLiteral(ShadowParser.LiteralContext ctx) {
    // no children visited
    Type type = literalToType(ctx);
    ctx.setType(type);
    if (type != Type.NULL) {
      currentType.addUsedType(type);
    }

    ctx.setInterpretedValue(processLiteral(ctx, getErrorReporter()));

    return null;
  }

  /**
   * Initializes a {@link ShadowValue} corresponding to the given literal node.
   *
   * <p>Also sets the appropriate {@link Type} on the node.
   */
  public static ShadowValue processLiteral(
      ShadowParser.LiteralContext ctx, ErrorReporter reporter) {
    // ugly but needed to find negation
    ParserRuleContext greatGrandparent = ctx.getParent().getParent().getParent();
    boolean negated = false;

    if (greatGrandparent instanceof ShadowParser.UnaryExpressionContext) {
      ParserRuleContext greatGreat = greatGrandparent.getParent();

      if (greatGreat instanceof ShadowParser.UnaryExpressionContext
          && greatGreat.getChild(0).getText().equals("-")) {
        negated = true;
      }
    }

    String literal = ctx.getText();
    String lower = literal.toLowerCase();
    int length = literal.length();

    try {
      if (literal.equals("null")) {
        return new ShadowNull(Type.NULL);
      } else if (literal.startsWith("'") && literal.endsWith("'")) {
        return ShadowCode.parseCode(literal);
      } else if (literal.startsWith("\"") && literal.endsWith("\"")) {
        return ShadowString.parseString(literal);
      } else if (literal.equals("true")) {
        return new ShadowBoolean(true);
      } else if (literal.equals("false")) {
        return new ShadowBoolean(false);
      } else if (lower.endsWith("f")
          && !lower.startsWith("0x")
          && !lower.startsWith("0c")
          && !lower.startsWith("0b")) {
        return ShadowFloat.parseFloat(lower.substring(0, length - 1));
      } else if (lower.endsWith("d")
          && !lower.startsWith("0x")
          && !lower.startsWith("0c")
          && !lower.startsWith("0b")) {
        return ShadowDouble.parseDouble(lower.substring(0, length - 1));
      } else if (literal.indexOf('.') != -1
          || (lower.indexOf('e') != -1
              && !lower.startsWith("0x")
              && !lower.startsWith("0c")
              && !lower.startsWith("0b"))) {
        return ShadowDouble.parseDouble(lower);
      } else {
        return ShadowInteger.parseNumber(lower, negated);
      }
    } catch (NumberFormatException e) {
      reporter.addError(ctx, Error.INVALID_LITERAL, "Value out of range: " + ctx.getText());
    } catch (IllegalArgumentException e) {
      reporter.addError(ctx, Error.INVALID_LITERAL, e.getLocalizedMessage());
    }

    return ShadowValue.INVALID;
  }

  @Override
  public Void visitPrimitiveType(ShadowParser.PrimitiveTypeContext ctx) {
    // no children
    ctx.setType(nameToPrimitiveType(ctx.getText()));
    currentType.addUsedType(ctx.getType());
    ctx.addModifiers(Modifiers.TYPE_NAME);

    return null;
  }

  @Override
  public Void visitReturnStatement(ShadowParser.ReturnStatementContext ctx) {
    visitChildren(ctx);

    if (currentMethod.isEmpty()) // Should never happen
    addError(ctx, Error.INVALID_STRUCTURE, "Return statement cannot be outside of method body");
    else {
      MethodType methodType = (MethodType) (currentMethod.getFirst().getType());
      ctx.setType(
          methodType.getReturnTypes()); // return statement set to exactly the types method returns
      // implicit casts are added in TAC conversion if the values don't match exactly

      if (currentMethod.getFirst() instanceof ShadowParser.DestroyDeclarationContext)
        // The reason you can't is that a destroy needs to add in reference decrements for each
        // field, at the end of the method
        // Using returns would complicate the code needed to insert these decrements, and also
        // destroys should be simple,
        // ideally created entirely by the compiler in most cases.
        addError(
            ctx,
            Error.INVALID_RETURNS,
            "Cannot use a return statement inside of a destroy definition");
      else if (methodType.getReturnTypes().isEmpty()) {
        if (ctx.rightSide() != null)
          addError(
              ctx,
              Error.INVALID_RETURNS,
              "Cannot return values from a method that returns nothing");
      } else {
        ShadowParser.RightSideContext child = ctx.rightSide();
        Type type = child.getType();
        SequenceType sequenceType;

        if (type instanceof SequenceType) sequenceType = (SequenceType) type;
        else sequenceType = new SequenceType(child);

        SequenceType updatedTypes = new SequenceType();

        // reconstitute full return types based on types with return modifiers
        // return modifiers can differ from regular modifiers because readonly and immutable methods
        // can enforce readonly constraints to keep object internals from changing during the method
        for (int i = 0; i < sequenceType.size(); ++i) {
          Modifiers modifiers = sequenceType.get(i).getModifiers();
          modifiers.removeModifier(Modifiers.TEMPORARY_READONLY);

          updatedTypes.add(new SimpleModifiedType(sequenceType.getType(i), modifiers));
        }

        if (!updatedTypes.isSubtype(ctx.getType()))
          addError(
              ctx,
              Error.INVALID_RETURNS,
              "Cannot return "
                  + updatedTypes
                  + " when "
                  + ctx.getType()
                  + (methodType.getReturnTypes().size() == 1 ? " is" : " are")
                  + " expected",
              ctx.getType(),
              updatedTypes);
        // update unbound method types
        else {
          for (int i = 0; i < updatedTypes.size(); i++) {
            Type leftType = methodType.getReturnTypes().get(i).getType();
            Type rightType = updatedTypes.get(i).getType();

            if (rightType instanceof UnboundMethodType unboundType && leftType instanceof MethodReferenceType) {
              MethodType returnMethodType = ((MethodReferenceType) leftType).getMethodType();
              MethodSignature signature =
                  unboundType
                      .getOuter()
                      .getMatchingMethod(
                          unboundType.getTypeName(), returnMethodType.getParameterTypes());
              unboundType.setKnownSignature(signature);
            }
          }
        }

        for (ModifiedType modifiedType : updatedTypes)
          if (modifiedType.getModifiers().isTypeName())
            addError(ctx, Error.INVALID_RETURNS, "Cannot return type name from a method");

        ParserRuleContext parent = ctx.getParent();
        boolean foundFinally = false;

        while (parent != null && !foundFinally) {
          // Trying to break or continue out of a finally
          if (parent instanceof ShadowParser.BlockContext
              && parent.getParent() instanceof ShadowParser.FinallyStatementContext)
            foundFinally = true;
          else parent = parent.getParent();
        }

        if (foundFinally)
          addError(ctx, Error.INVALID_STRUCTURE, "Cannot return from inside a finally block");
      }
    }

    return null;
  }

  @Override
  public Void visitResultType(ShadowParser.ResultTypeContext ctx) {
    visitChildren(ctx);

    Type type = ctx.type().getType();
    ctx.setType(type);

    return null;
  }

  @Override
  public Void visitExplicitCreateInvocation(ShadowParser.ExplicitCreateInvocationContext ctx) {
    visitChildren(ctx);

    SequenceType arguments = new SequenceType();
    arguments.addAll(ctx.conditionalExpression());

    if (currentType instanceof ClassType type) {
      // assumes "this"
      if (ctx.getChild(0).getText().equals("super")) {
        if (type.getExtendType() != null) type = type.getExtendType();
        else
          addError(
              ctx,
              Error.INVALID_CREATE,
              "Class type "
                  + type
                  + " cannot invoke a parent create because it does not extend another type",
              type);
      }

      // Set ctx type inside
      MethodSignature signature = setMethodType(ctx, type, "create", arguments);
      ctx.setSignature(signature);
    } else
      addError(
          ctx,
          Error.INVALID_CREATE,
          "Non-class type " + currentType + " cannot be created",
          currentType);

    return null;
  }

  @Override
  public Void visitType(ShadowParser.TypeContext ctx) {
    visitChildren(ctx);

    Context child = (Context) ctx.getChild(0);
    ctx.addModifiers(child.getModifiers());

    boolean isNullable = ctx.getModifiers().isNullable();

    Type type = child.getType();

    if (isNullable) {
      if (type instanceof ArrayType arrayType) {
        type = arrayType.convertToNullable();

        if (arrayType.recursivelyGetBaseType().isPrimitive())
          addError(
              ctx,
              Error.INVALID_MODIFIER,
              "Primitive array type " + type + " cannot be marked nullable");
      }
    }

    ctx.setType(type);
    currentType.addUsedType(type);

    return null;
  }

  @Override
  public Void visitIsList(ShadowParser.IsListContext ctx) {
    return null; // no children
  }

  @Override
  public Void visitInlineMethodDefinition(ShadowParser.InlineMethodDefinitionContext ctx) {
    visitMethodPre(ctx);
    visitChildren(ctx);

    MethodType methodType = ctx.getSignature().getMethodType();

    // add parameters
    ShadowParser.FormalParametersContext parameters = ctx.formalParameters();
    for (ShadowParser.FormalParameterContext parameter : parameters.formalParameter())
      methodType.addParameter(parameter.Identifier().getText(), parameter);

    // add return types
    ShadowParser.InlineResultsContext results = ctx.inlineResults();

    for (ShadowParser.ConditionalExpressionContext result : results.conditionalExpression())
      methodType.addReturn(result);

    ctx.setType(methodType);

    visitMethodPost();

    return null;
  }

  @Override
  public Void visitArrayDimensions(ShadowParser.ArrayDimensionsContext ctx) {
    visitChildren(ctx);

    for (ShadowParser.ConditionalExpressionContext child : ctx.conditionalExpression()) {
      Type childType = child.getType();

      if (!childType.isIntegral()) {
        addError(
            child,
            Error.INVALID_SUBSCRIPT,
            "Supplied type "
                + childType
                + " cannot be used in this array creation, integral type required",
            childType);
        ctx.setType(Type.UNKNOWN);
        return null;
      }
    }

    return null;
  }

  @Override
  public Void visitMethodDeclarator(ShadowParser.MethodDeclaratorContext ctx) {
    // Non-local methods have already been handled in the type updater
    if (ctx.getParent() instanceof LocalMethodDeclarationContext parent) {
      visitChildren(ctx);

      MethodSignature signature = parent.getSignature();

      // Add parameters
      for (ShadowParser.FormalParameterContext parameter : ctx.formalParameters().formalParameter())
        signature.addParameter(parameter.Identifier().getText(), parameter);

      // Add return types
      for (ShadowParser.ResultTypeContext result : ctx.resultTypes().resultType())
        signature.addReturn(result);

      ctx.setType(signature.getMethodType());
    }

    return null;
  }

  @Override
  public Void visitSpawnExpression(ShadowParser.SpawnExpressionContext ctx) {
    visitChildren(ctx);

    currentType.addUsedType(Type.THREAD);
    currentType.addUsedType(Type.THREAD_CURRENT);

    ShadowParser.ConditionalExpressionContext runner;
    SequenceType sequence = new SequenceType();

    if (ctx.conditionalExpression().size() == 2) { // Optional thread name
      ShadowParser.ConditionalExpressionContext name = ctx.conditionalExpression().get(0);

      if (name.getType() != Type.STRING)
        addError(name, Error.INVALID_TYPE, "Optional thread name must have type " + Type.STRING);
      else
        sequence.add(name);

      runner = ctx.conditionalExpression().get(1);
    }
    else
      runner = ctx.conditionalExpression().get(0);

    Type runnerType = runner.getType();
    if( !runnerType.isSubtype(Type.CAN_RUN) ) {
      addError(
              runner,
          Error.INVALID_SPAWN,
          "Type "
              + runnerType
              + " cannot be spawned because it is not a subtype of the "
              + Type.CAN_RUN
              + " interface",
              runnerType);
    }
    else if(runner.getModifiers().isTypeName()) {
      addError(runner, Error.INVALID_SPAWN, "Type name cannot be spawned", runnerType);
    }
    else {
      sequence.add(runner);
      ctx.setSignature(Type.THREAD.getMatchingMethod("create", sequence));
    }

    ctx.setType(Type.THREAD);

    return null;
  }

  @Override
  public Void visitSendStatement(SendStatementContext ctx) {
    visitChildren(ctx);

    if (!resolveType(ctx.conditionalExpression(1)).getType().equals(Type.THREAD)) {
      addError(
          ctx,
          Error.INVALID_ARGUMENTS,
          "Cannot send to a non-Thread object");
    } else {
      if (ctx.conditionalExpression(1).getModifiers().isNullable()) {
        addError(
                ctx,
                Error.INVALID_MODIFIER,
                "Cannot send to a nullable Thread");
      }


      List<ShadowException> errors = new ArrayList<>();

      MethodSignature sendSignature =
          Type.THREAD.getMatchingMethod(
              "sendTo",
              new SequenceType(Arrays.asList(resolveType(ctx.conditionalExpression().get(0)), new SimpleModifiedType(Type.BOOLEAN))),
              new SequenceType(),
              errors);
      if (sendSignature == null) {
        addError(
            ctx,
            Error.INVALID_ARGUMENTS,
            "The arguments do not match the signature: send(Object data, Thread to)");
      } else {
        ctx.setSignature(sendSignature);
        ctx.setType(sendSignature.getReturnTypes()); // void
      }
    }

    return null;
  }

  @Override public Void visitReceiveExpression(shadow.parse.ShadowParser.ReceiveExpressionContext ctx) {
    visitChildren(ctx);
    MethodSignature receiveSignature = null;
    List<ShadowException> errors = new ArrayList<>();
    ctx.setType(Type.UNKNOWN);

    if(ctx.conditionalExpression() != null) {
      Type type = ctx.conditionalExpression().getType();
      if (!type.isSubtype(Type.THREAD) || ctx.conditionalExpression().getModifiers().isTypeName())
        addError(
             ctx,
             Error.INVALID_ARGUMENTS,
             "The argument of a receive expression must be a Thread object");
      else {
        receiveSignature =
                Type.THREAD.getMatchingMethod(
                        "receiveFirstFrom",
                        new SequenceType(Arrays.asList(new SimpleModifiedType(Type.CLASS), new SimpleModifiedType(Type.THREAD), new SimpleModifiedType(Type.BOOLEAN))),
                        new SequenceType(),
                        errors);
        if (receiveSignature == null) {
          addError(
                  ctx,
                  Error.INVALID_ARGUMENTS,
                  "The receive arguments do not match the syntax receive<type>( thread )");
        }
        else
          ctx.setType(ctx.type().getType());
      }
    }
    else {
      receiveSignature =
              Type.THREAD.getMatchingMethod(
                      "receiveFirst",
                      new SequenceType(Arrays.asList(new SimpleModifiedType(Type.CLASS), new SimpleModifiedType(Type.BOOLEAN))),
                      new SequenceType(),
                      errors);
      if (receiveSignature == null) { // should be impossible
        addError(
                ctx,
                Error.INVALID_ARGUMENTS,
                "The receive arguments do not match the syntax receive<type>()");
      }
      else
        ctx.setType(new SequenceType(Arrays.asList(ctx.type(), new SimpleModifiedType(Type.THREAD))));
    }

    ctx.setSignature(receiveSignature);




  	return null;
  }

  @Override
  public Void visitAttributeInvocation(ShadowParser.AttributeInvocationContext ctx) {
    visitChildren(ctx);

    // always part of a suffix, thus always has a prefix
    Type prefixType = ctx.classOrInterfaceType().getType();

    SequenceType arguments = new SequenceType();
    arguments.addAll(ctx.conditionalExpression());

    if (prefixType instanceof AttributeType attributeType) {

      MethodSignature signature = setCreateType(ctx, prefixType, arguments);
      ctx.setSignature(signature);

      if (signature != null)
        ctx.setType(attributeType);
      else
        ctx.setType(Type.UNKNOWN);
    } else {
      addError(
              ctx,
              Error.INVALID_TYPE,
              "Cannot invoke non-attribute type " + prefixType + " as an attribute",
              prefixType);
      ctx.setType(Type.UNKNOWN);
    }

    return null;
  }

  @Override
  public Void visitVariableDeclarator(ShadowParser.VariableDeclaratorContext ctx) {
    // Must happen first to determine the types of conditional expressions
    visitChildren(ctx);

    return null;
  }

  @Override
  public Void visitDependencyList(ShadowParser.DependencyListContext ctx) {
    // These only appear in .meta files and don't require statement-checking
    return null;
  }

  @Override
  public Void visitGenericTypeList(GenericTypeListContext ctx) {
    visitChildren(ctx);

    // Get type for top-level declaration
    CompilationUnitContext compilationUnit = (CompilationUnitContext) ctx.getParent();
    Context declaration = compilationUnit.classOrInterfaceDeclaration();
    if (declaration == null)
      declaration = compilationUnit.enumDeclaration();
    Type type = declaration.getType();

    for (ClassOrInterfaceTypeContext child : ctx.classOrInterfaceType())
      type.addGenericType(child.getType());

    return null;
  }
}
