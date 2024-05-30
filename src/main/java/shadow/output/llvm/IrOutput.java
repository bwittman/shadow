package shadow.output.llvm;

import shadow.Configuration;
import shadow.ShadowException;
import shadow.interpreter.*;
import shadow.output.AbstractOutput;
import shadow.tac.*;
import shadow.tac.TACMethod.TACFinallyFunction;
import shadow.tac.nodes.*;
import shadow.typecheck.type.*;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;

public class IrOutput extends AbstractOutput {
  private int tempCounter = 0;
  private final List<String> stringLiterals = new LinkedList<>();
  private int classCounter = 0;
  private final HashSet<MethodSignature> usedSignatures = new HashSet<>();
  private final Set<ExceptionType> exceptions = new TreeSet<>();
  private final Set<Type> instantiatedGenerics;

  private TACModule module;
  private boolean skipMethod = false;

  @SuppressWarnings("unused")
  public IrOutput(Path file, Set<Type> instantiatedGenerics) throws ShadowException {
    super(file);
    this.instantiatedGenerics = instantiatedGenerics;
  }

  public IrOutput(OutputStream stream, Set<Type> instantiatedGenerics) {
    super(stream);
    this.instantiatedGenerics = instantiatedGenerics;

  }

  private String temp(int offset) {
    return '%' + Integer.toString(tempCounter - offset - 1);
  }

  private String nextTemp() {
    return '%' + Integer.toString(tempCounter++);
  }

  /**
   * Determines either the next temporary variable or the local variable that will store the result
   * of this action. That name is stored in the data member of the node.
   *
   * <p>Caution: Calling this method *may* or *may not* update the current temporary counter.
   *
   * @param node node that will get the temporary variable name
   * @return String giving the variable name where the operation is stored
   */
  private String nextTemp(TACOperand node) {
    String name;
    if (node.hasLocalStore()) name = name(node.getLocalStore());
    else name = nextTemp();
    node.setData(name);
    return name;
  }

  // Class type flags
  public static final int INTERFACE = 1,
      PRIMITIVE = 2,
      GENERIC = 4,
      ARRAY = 8,
      SINGLETON = 16,
      METHOD = 32,
      ATTRIBUTE = 64;

  private void writePrimitiveTypes() throws ShadowException {
    writer.write("%boolean = type i1");
    writer.write("%byte = type i8");
    writer.write("%ubyte = type i8");
    writer.write("%short = type i16");
    writer.write("%ushort = type i16");
    writer.write("%int = type i32");
    writer.write("%uint = type i32");
    writer.write("%code = type i32");
    writer.write("%long = type i64");
    writer.write("%ulong = type i64");
    writer.write("%float = type float");
    writer.write("%double = type double");
  }

  private Set<Type> writeUsedTypes() throws ShadowException {
    writePrimitiveTypes();

    Type moduleType = module.getType();

    writer.write();
    writer.write("; Used types");
    // type references for regular types
    HashSet<Type> definedGenerics = new HashSet<>();
    Set<Type> usedTypes = moduleType.getUsedTypes();
    for (Type type : moduleType.getUsedTypes()) {
      if (!type.isParameterized()
          && !(type instanceof ArrayType)
          && !(type instanceof MethodReferenceType)) {
        writeTypeDefinition(type);

        // external stuff for types outside of this file
        if (!moduleType.encloses(type)) {
          writer.write(classOf(type) + " = external constant %" + raw(Type.CLASS));
          if (type instanceof ClassType)
            writer.write(
                methodTable(type) + " = external constant " + methodTableType(type, false));
          if (type instanceof SingletonType) // never parameterized
            writer.write(
                '@' + raw(type, ".singleton") + " = external thread_local global " + type(type));
        }
      }

      if (type.isParameterized() && !(type instanceof ArrayType)) {
        type = type.getTypeWithoutTypeArguments();
        if (definedGenerics.add(type)) {
          writeTypeDefinition(type);
          writeGenericInterfaceDataReference(type);
        }
      }
    }

    writer.write();

    return definedGenerics;
  }

  private void writeMentionedTypes(Set<Type> definedGenerics) throws ShadowException {
    Type moduleType = module.getType();

    for (Type type : moduleType.getMentionedTypes()) {
      if (type != null
          && !(type instanceof ArrayType)
          && !(type instanceof MethodReferenceType)
          && !moduleType.getUsedTypes().contains(type)) {
        if (!type.isParameterized()) {
          writeTypeDeclaration(type);

          // external stuff for types outside of this file
          if (!moduleType.encloses(type))
            writer.write(classOf(type) + " = external constant %" + raw(Type.CLASS));
        } else {
          Type unparameterizedType = type.getTypeWithoutTypeArguments();
          // If unparameterized version has not been declared yet, do it
          if (definedGenerics.add(unparameterizedType)) writeTypeDeclaration(unparameterizedType);
        }
      }
    }

    writer.write();
  }


  private void writeTypeDeclaration(Type type) throws ShadowException {
    StringBuilder sb = new StringBuilder();
    if (type instanceof InterfaceType) {
      sb.append(methodTableType(type, false)).append(" = type opaque");
      writer.write(sb.toString());
    } else if (type instanceof ClassType) {
      if (type.isUninstantiated()) {
        sb.append('%').append(raw(type)).append(" = type opaque");
        writer.write(sb.toString());
      }
    }
  }

  private void writeMethodTableDeclaration(Type type) throws ShadowException {
    if (type instanceof ClassType) {
      if (type.isUninstantiated()) {
        writer.write(methodTableType(type, false) + " = type opaque");
      }
    }
  }


  /* This method is similar to the writeTypeDefinition() method below, but it
   * treats all objects like Type.OBJECT and all interfaces like a
   * {Type.METHOD_TABLE, Type.OBJECT}. Only primitive types are written out normally.
   * This kind of type definition is enough for a class object to calculate the size
   * of the underlying type.
   */
  private void writeSimpleTypeDefinition(Type type) throws ShadowException {
    StringBuilder sb = new StringBuilder();

    if (type instanceof ClassType classType) {
      if (type.isUninstantiated()) {

        // First thing in every object is the reference count
        sb.append('%').append(raw(type)).append(" = type { ");
        sb.append(type(Type.ULONG)).append(", ");

        // Second thing in every object is the class
        sb.append(type(Type.CLASS)).append(", ");

        // Then the method table
        sb.append(type(Type.METHOD_TABLE));

        if (type.isPrimitive()) // put wrapped value in for primitives
          sb.append(", ").append(type(type));
        else {
          for (Entry<String, ? extends ModifiedType> field : classType.orderAllFields()) {
            ModifiedType modifiedType = field.getValue();
            sb.append(", ");
            if (modifiedType.getType() instanceof InterfaceType)
              sb.append(anonymousInterfaceType());
            else if(modifiedType.getType().isPrimitive())
              sb.append(type(modifiedType));
            else
              sb.append(type(Type.OBJECT));
          }
        }
        writer.write(sb.append(" }").toString());
      }
    }
  }

  private void writeTypeDefinition(Type type) throws ShadowException {
    StringBuilder sb = new StringBuilder();

    boolean useRealMethodTable = module != null;

    if (type instanceof InterfaceType) {

      if (useRealMethodTable) {
        sb.append(methodTableType(type, false)).append(" = type { ");
        writer.write(sb.append(methodList(type.orderAllMethods(), false)).append(" }").toString());
      }

    } else if (type instanceof ClassType) {
      if (type.isUninstantiated()) {

        if (useRealMethodTable) {
          sb.append(methodTableType(type, false)).append(" = type { ");
          writer.write(
              sb.append(methodList(type.orderAllMethods(), false)).append(" }").toString());
        }

        sb.setLength(0);

        // First thing in every object is the reference count
        sb.append('%').append(raw(type)).append(" = type { ");
        sb.append(type(Type.ULONG)).append(", ");

        // Second thing in every object is the class
        sb.append(type(Type.CLASS)).append(", ");

        // Then the method table

        if (useRealMethodTable)
          sb.append(methodTableType(type));
        else
          sb.append(type(Type.METHOD_TABLE));

        if (type.isPrimitive()) // put wrapped value in for primitives
          sb.append(", ").append(type(type));
        else {
          for (Entry<String, ? extends ModifiedType> field : ((ClassType) type).orderAllFields())
            sb.append(", ").append(type(field.getValue()));
        }
        writer.write(sb.append(" }").toString());
      }
    }
  }

  private void writeModuleDefinition(TACModule module) throws ShadowException {
    Type moduleType = module.getType();
    writer.write(
        "; " + moduleType.toString(Type.PACKAGES | Type.TYPE_PARAMETERS | Type.PARAMETER_BOUNDS));
    writer.write();

    // Class fields (since the order is all changed)
    // used for debugging
    if (moduleType instanceof ClassType classType) {
      writer.write("; 0: reference count (ulong)");
      writer.write("; 1: class (Class)");
      writer.write("; 2: ._methods");
      int counter = 3;
      for (Entry<String, ? extends ModifiedType> field : (classType).orderAllFields()) {

        writer.write(
            "; " + counter + ": " + field.getKey() + " (" + field.getValue().getType() + ")");
        ++counter;
      }

      writer.write();
    }

    // Constants
    // TODO: Change private constants to private?
    for (TACConstant constant : module.getConstants()) {
      writer.write(
          name(constant)
              + " = unnamed_addr constant "
              + typeLiteral(constant.getInterpretedValue()));
    }

    // interfaces implemented (because a special object is used to map the methods correctly)
    ArrayList<InterfaceType> interfaces = moduleType.getAllInterfaces();
    long interfaceCount = interfaces.size();

    if (module.isClass()) {
      // no need to write interface data for interfaces
      StringBuilder interfaceData = new StringBuilder(interfaceData(moduleType) + " = ");

      // if( !moduleType.isParameterizedIncludingOuterClasses() )
      // interfaceData.append("private " );

      // generic classes don't list interfaces (because their parameterized versions have those)
      // but they do share interfaceData (the actual methods)
      interfaceData
          .append("unnamed_addr constant { %ulong, ")
          .append(type(Type.GENERIC_CLASS))
          .append(",")
          .append(methodTableType(Type.ARRAY))
          .append(", %long, [")
          .append(interfaceCount)
          .append(" x ")
          .append(type(Type.METHOD_TABLE))
          .append("]} { %ulong -1, ")
          .append(type(Type.GENERIC_CLASS))
          .append(" ")
          .append(classOf(new ArrayType(Type.METHOD_TABLE)))
          .append(", ")
          .append(methodTableType(Type.ARRAY))
          .append(" ")
          .append(methodTable(Type.ARRAY))
          .append(", ")
          .append(typeLiteral(interfaceCount))
          .append(", [")
          .append(interfaceCount)
          .append(" x ")
          .append(type(Type.METHOD_TABLE))
          .append("] [");
      StringBuilder interfaceClasses = new StringBuilder(interfaces(moduleType) + " = private ");

      interfaceClasses
          .append("unnamed_addr constant { %ulong, ")
          .append(type(Type.GENERIC_CLASS))
          .append(",")
          .append(methodTableType(Type.ARRAY))
          .append(", %long, [")
          .append(interfaceCount)
          .append(" x ")
          .append(type(Type.CLASS))
          .append("]} { %ulong -1, ")
          .append(typeText(Type.GENERIC_CLASS, classOf(new ArrayType(Type.CLASS))))
          .append(", ")
          .append(methodTableType(Type.ARRAY))
          .append(" ")
          .append(methodTable(Type.ARRAY))
          .append(", ")
          .append(typeLiteral(interfaceCount))
          .append(", [")
          .append(interfaceCount)
          .append(" x ")
          .append(type(Type.CLASS))
          .append("] [");

      boolean firstData = true;
      boolean firstClass = true;

      for (InterfaceType type : interfaces) {
        if (module.isClass()) {
          List<MethodSignature> methods = type.orderAllMethods(module.getClassType());

          String methodsType = methodList(methods, false);
          writer.write(
              "@_class"
                  + classCounter
                  + " = private unnamed_addr constant { "
                  + methodsType
                  + " } { "
                  + methodList(methods, true)
                  + " }");
          if (firstData) firstData = false;
          else interfaceData.append(", ");
          interfaceData
              .append(type(Type.METHOD_TABLE))
              .append(" bitcast ({ ")
              .append(methodsType)
              .append(" }* @_class")
              .append(classCounter)
              .append(" to ")
              .append(type(Type.METHOD_TABLE))
              .append(")");
        }

        if (firstClass) firstClass = false;
        else interfaceClasses.append(", ");

        if (type.isFullyInstantiated())
          interfaceClasses.append(
              typeText(
                  Type.CLASS,
                  "bitcast ("
                      + type(Type.GENERIC_CLASS)
                      + " "
                      + classOf(type)
                      + " to "
                      + type(Type.CLASS)
                      + ")"));
        else interfaceClasses.append(typeText(Type.CLASS, classOf(type)));
        classCounter++;
      }
      writer.write(interfaceData.append("]}").toString());

      if (!moduleType.isParameterized()) writer.write(interfaceClasses.append("]}").toString());
    }

    // method declarations (but not definitions)
    List<MethodSignature> methods = moduleType.orderAllMethods();

    int flags = 0;
    if (moduleType.isPrimitive()) flags |= PRIMITIVE;
    if (moduleType instanceof SingletonType) flags |= SINGLETON;
    if (moduleType instanceof AttributeType) flags |= ATTRIBUTE;
    if (module.isClass()) {
      // add in methods that are inherited from parent classes
      for (MethodSignature signature : methods)
        if ((signature.getOuter() instanceof ClassType)
            && !module.getType().encloses(signature.getOuter())
            && !signature.isWrapper()) usedSignatures.add(signature);

      assert moduleType instanceof ClassType;
      ClassType parentType = ((ClassType) moduleType).getExtendType();
      writer.write(
          methodTable(moduleType)
              + " = unnamed_addr constant "
              + methodTableType(moduleType, false)
              + " { "
              + methodList(methods, true)
              + " }");

      // nothing will ever be the raw, unparameterized class
      if (!moduleType.isParameterized()) {
        writer.write(
            classOf(moduleType)
                + " = unnamed_addr constant %"
                + raw(Type.CLASS)
                + " { "
                + type(Type.ULONG)
                + " "
                + literal(-1)
                + ", "
                + // reference count
                type(Type.CLASS)
                + " "
                + classOf(Type.CLASS)
                + ", "
                + // class
                methodTableType(Type.CLASS)
                + " "
                + methodTable(Type.CLASS)
                + ", "
                + // methods
                type(Type.ARRAY)
                + " bitcast ({%ulong, "
                + type(Type.GENERIC_CLASS)
                + ", "
                + methodTableType(Type.ARRAY)
                + ", %long, ["
                + interfaceCount
                + " x "
                + type(Type.METHOD_TABLE)
                + "]}* "
                + interfaceData(moduleType)
                + " to "
                + type(Type.ARRAY)
                + "), "
                + type(Type.ARRAY)
                + // interfaces
                (moduleType.isParameterized()
                    ? " null, "
                    : "bitcast ({%ulong, "
                        + type(Type.GENERIC_CLASS)
                        + ", "
                        + methodTableType(Type.ARRAY)
                        + ", %long, ["
                        + interfaceCount
                        + " x "
                        + type(Type.CLASS)
                        + "]}* "
                        + interfaces(moduleType)
                        + " to "
                        + type(Type.ARRAY)
                        + "), ")
                + typeLiteral(moduleType.toString(Type.PACKAGES))
                + ", "
                + // name
                typeText(
                    Type.CLASS,
                    parentType != null
                        ? // parent class
                        classOf(parentType)
                        : null)
                + ", "
                + typeLiteral(flags)
                + ", "
                + // flags
                typeText(Type.INT, sizeof(moduleType))
                + // size
                " }");
      }
    } else if (!(moduleType instanceof AttributeType)) {
      flags |= INTERFACE;

      // nothing will ever be the raw, unparameterized class
      if (!moduleType.isParameterized()) {
        writer.write(
            classOf(moduleType)
                + " = unnamed_addr constant %"
                + raw(Type.CLASS)
                + " { "
                + type(Type.ULONG)
                + " "
                + literal(-1)
                + ", "
                + // reference count
                type(Type.CLASS)
                + " "
                + classOf(Type.CLASS)
                + ", "
                + // class
                methodTableType(Type.CLASS)
                + " "
                + methodTable(Type.CLASS)
                + ", "
                + // methods
                type(Type.ARRAY)
                + " null, "
                + // interfaceTables
                type(Type.ARRAY)
                + " null, "
                + // interfaces
                typeLiteral(moduleType.toString(Type.PACKAGES))
                + ", "
                + // name
                type(Type.CLASS)
                + " null, "
                + // parent
                typeLiteral(flags)
                + ", "
                + typeText(Type.INT, sizeof(moduleType))
                + // size
                " }");
      }
    }

    if (moduleType instanceof SingletonType)
      writer.write(
          '@'
              + raw(moduleType, ".singleton")
              + " = thread_local global "
              + type(moduleType)
              + " null");

    writer.write();

    // Recursively do inner classes
    for (TACModule innerClass : module.getInnerClasses()) writeModuleDefinition(innerClass);
  }

  // Windows only
  private void writeExceptions() throws ShadowException {
    writer.write("declare i32 @__exceptionFilter(i8*, i8*, " + type(Type.CLASS) + ")");

    for (ExceptionType exceptionType : exceptions) {
      writer.write(exceptionComdat(exceptionType) + " = comdat any");
      writer.write(
          "define linkonce_odr i32 "
              + exceptionMethod(exceptionType)
              + "(i8* %0, i8* %1) comdat {");
      writer.indent();
      writer.write(
          "%3 = call i32 @__exceptionFilter(i8* %0, i8* %1, "
              + type(Type.CLASS)
              + " "
              + classOf(exceptionType)
              + ")");
      writer.write("ret i32 %3");
      writer.outdent();
      writer.write("}");
    }
  }

  private static boolean addReferencedType(Set<Type> referencedTypes, Type type) {
    // Ignore if parameterized (because we only need unparameterized types)
    // Ignore if an array for the same reasons (arrays are essentially generic types)
    if (!type.isParameterized() && !(type instanceof ArrayType))
      return referencedTypes.add(type);
    return false;
  }

  /*
   * The generic class file contains:
   * 1) Primitive types (as usual)
   * 2) Full definitions for GenericClass, String, and Array types
   * 3) External class constants for any (non-generic) type arguments
   * 4) Partial definitions for generics with type arguments removed
   * 5) Class constants for generics
   */
  public void writeGenericClassFile() throws ShadowException {
    Set<Type> generics = new HashSet<>(instantiatedGenerics);
    Set<Type> updatedGenerics = new HashSet<>(generics);
    // Gather all generics, including the ones in dependency lists of known generics
    do {
      for (Type type : generics) {
          if(type instanceof ArrayType arrayType)
            type = arrayType.convertToGeneric();

          if (type instanceof ClassType classType) {
            if (classType.hasDependencyList()) {
              for (ModifiedType parameter : classType.getDependencyList()) {
                Type parameterType = parameter.getType();
                if (parameterType instanceof ArrayType arrayType)
                  parameterType = arrayType.convertToGeneric();

                if (parameterType.isFullyInstantiated()) updatedGenerics.add(parameterType);
              }
            }
          }
      }

      Set<Type> temp = generics;
      generics = updatedGenerics;
      updatedGenerics = temp;

    } while(generics.size() != updatedGenerics.size());

    writePrimitiveTypes();

    Set<Type> typeArguments = new HashSet<>();

    // For generic class internals, all we really need are the following, NOT core types:
    Set<Type> mainTypes = new HashSet<>(Arrays.asList(Type.GENERIC_CLASS, Type.STRING));
    for (Type type : mainTypes) {
      writeTypeDefinition(type);
      typeArguments.add(type);
    }

    /*
    Set<Type> mainGenerics = new HashSet<>(Arrays.asList(Type.ARRAY, Type.ITERATOR));
    for (Type type : mainGenerics) {
      writeTypeDefinition(type);
      writeGenericInterfaceDataReference(type);
    }

     */

    // There are a few more types that we need but don't need to see the real internals of:
    Set<Type> extraTypes = new HashSet<>(Arrays.asList(Type.ADDRESS_MAP, Type.METHOD_TABLE, Type.ULONG, Type.OBJECT, Type.CLASS));
    for (Type type : extraTypes) {
      writeSimpleTypeDefinition(type);
      typeArguments.add(type);
    }

    Set<Type> usedGenerics = new HashSet<>(Arrays.asList(Type.ARRAY, Type.ITERATOR));
    Set<Type> unparameterizedGenerics = new HashSet<>();

    for (Type type : generics) {
      Type unparameterized = type.getTypeWithoutTypeArguments();
      usedGenerics.add(unparameterized);

      for (ModifiedType parameter : type.getTypeParameters()) {
        Type typeParameter = parameter.getType();
        if (!typeParameter.isParameterized() && !(typeParameter instanceof ArrayType))
          typeArguments.add(typeParameter);
      }

      // Though not technically a type argument, the interface classes must be known as well
      for (InterfaceType interfaceType : type.getInterfaces()) {
        if (!interfaceType.isParameterized())
          typeArguments.add(interfaceType);
      }

      /*
      Type uninstantiatedType = type.getTypeWithoutTypeArguments();
      if (usedGenerics.add(uninstantiatedType) ) {
        if (uninstantiatedType instanceof ClassType classType)
          for (ModifiedType modifiedType : classType.getAllFields())
            addReferencedType(referencedTypes, modifiedType.getType());
      */
        /*
        for (MethodSignature signature : uninstantiatedType.orderAllMethods()) {
          for(ModifiedType modifiedType : signature.getParameterTypes())
            addReferencedType(referencedTypes, modifiedType.getType());
          for(ModifiedType modifiedType : signature.getReturnTypes())
            addReferencedType(referencedTypes, modifiedType.getType());
        }
         */

  /*
      if (type instanceof ArrayType arrayType)
        type = arrayType.convertToGeneric();


      for (ModifiedType parameter : type.getTypeParameters())
          addReferencedType(referencedTypes, parameter.getType());

       */



    }


    /*
    List<Type> supportingTypes = Type.getGenericSupportingTypes();
    writer.write("; Supporting class objects");
    for(Type type : supportingTypes)
      writer.write(classOf(type) + " = external constant %" + raw(Type.CLASS));

    // Always needed
    usedTypes.addAll(supportingTypes);
*/

    /*
    writer.write("; Core types");
    List<Type> coreTypes = Type.getCoreTypes();
    for(Type coreType : coreTypes) {
      if(coreType instanceof ArrayType arrayType)
        coreType = arrayType.convertToGeneric();

      if (coreType.isFullyInstantiated()) instantiatedGenerics.add(coreType);
      else {
        writeTypeDefinition(coreType, false, false);
        writer.write(methodTable(coreType) + " = external constant %" + raw(Type.METHOD_TABLE));
        usedGenerics.remove(coreType);
      }
      referencedTypes.remove(coreType);
    }


     */


    /*


    // Writing an unparameterized generic removes all type parameters
    // and writes a definition and interface information
    writer.write("; Unparameterized generics");
    for (Type type : usedGenerics) {
      //writeUnparameterizedGeneric
      writeUnparameterizedGenericDeclaration(type, unparameterizedGenerics);
      referencedTypes.remove(type);
    }

     */



    writer.write();
    writer.write("; Type arguments");
    for(Type type : typeArguments) {
      if (type instanceof ClassType)
        writer.write(methodTable(type) + " = external constant %" + raw(Type.METHOD_TABLE));
      writer.write(classOf(type) + " = external constant %" + raw(Type.CLASS));
    }

    writer.write();
    writer.write("; Unparameterized generics");
    // 4) Partial definitions for generics with type arguments removed
    for(Type type : usedGenerics) {
        //writeUnparameterizedGeneric(type, false);

        writeSimpleTypeDefinition(type);
        writeGenericInterfaceDataReference(type);
    }

    // 5) Class constants for generics
    // This is the point of this method.
    // All the previous content is supporting material so that none of these
    // reference a type that doesn't exist.
    writer.write();
    writer.write("; Fully parameterized generics");
    for (Type type : generics) {
      writeGenericClassSupportingMaterial(type);
      writeGenericClass(type);
    }

    writeStringLiterals();
  }

  private void recordGenericClasses(Set<Type> definedGenerics) throws ShadowException {

    Set<Type> genericClasses = module.getType().getInstantiatedGenerics();

    if (definedGenerics.add(Type.ARRAY)) {
      writeTypeDefinition(Type.ARRAY);
      writeGenericInterfaceDataReference(Type.ARRAY);
    }


    if(definedGenerics.add(Type.ARRAY_NULLABLE)) {
      writeTypeDefinition(Type.ARRAY_NULLABLE);
      writeGenericInterfaceDataReference(Type.ARRAY_NULLABLE);
    }


    for (Type type : genericClasses) {
      instantiatedGenerics.add(type);
      declareGenericClass(type);

      // write type and method table declarations (even for current types!)
      if (!(type instanceof ArrayType)) {
        type = type.getTypeWithoutTypeArguments();
        if (definedGenerics.add(type)) {
          writeTypeDefinition(type);
          writeGenericInterfaceDataReference(type);
        }
      }

      //writeGenericClassSupportingMaterial(type); // junk that all generic classes need
      //writeGenericClass(type);
    }

    writer.write();
  }


 /*
  private void writeUnparameterizedGeneric(Type type) throws ShadowException {
    writeUnparameterizedGeneric(type, true);
  }

  */

  private void writeGenericInterfaceDataReference(Type type) throws ShadowException {
    boolean useRealMethodTable = module != null;

    // module can be null for file that declares all parameterized generics
    if (module == null || !module.getType().encloses(type)) {
      if (type instanceof ClassType) {
        writer.write(
                interfaceData(type)
                        + " = external constant { %ulong, "
                        + type(Type.GENERIC_CLASS)
                        + ", "
                        + (useRealMethodTable ? methodTableType(Type.ARRAY) : type(Type.METHOD_TABLE))
                        + ", %long, ["
                        + type.getAllInterfaces().size()
                        + " x "
                        + type(Type.METHOD_TABLE)
                        + "]}");

        if (!(type instanceof ArrayType)) {
          if (useRealMethodTable)
            writer.write(methodTable(type) + " = external constant " + methodTableType(type, false));
          else
            writer.write(methodTable(type) + " = external constant %" + raw(Type.METHOD_TABLE));
        }
      }
    }
  }

  /*
  private void writeUnparameterizedGeneric(Type type, boolean useRealMethodTableType)
      throws ShadowException {

    writeTypeDefinition(type, !(type instanceof ArrayType), useRealMethodTableType);

  }

   */

  private void writeUnparameterizedGenericDeclaration(Type type, Set<Type> unparameterizedGenerics)
          throws ShadowException {
    Type unparameterizedType = type.getTypeWithoutTypeArguments();

    if (unparameterizedGenerics.add(unparameterizedType)) {
      writeSimpleTypeDefinition(unparameterizedType);

      // module can be null for file that declares all parameterized generics
      if (module == null || !module.getType().encloses(unparameterizedType)) {
        if (unparameterizedType instanceof ClassType) {
          writer.write(
                  interfaceData(unparameterizedType)
                          + " = external constant { %ulong, "
                          + type(Type.GENERIC_CLASS)
                          + ", "
                          + methodTableType(Type.ARRAY)
                          + ", %long, ["
                          + unparameterizedType.getAllInterfaces().size()
                          + " x "
                          + type(Type.METHOD_TABLE)
                          + "]}");

          if (!(unparameterizedType instanceof ArrayType))
            writer.write(
                    methodTable(unparameterizedType)
                            + " = external constant %"
                           // + methodTableType(unparameterizedType, false));
                            + raw(Type.METHOD_TABLE));
        }
      }
    }
  }

  @Override
  public void startFile(TACModule module) throws ShadowException {
    this.module = module;

    if (module.getType() instanceof AttributeType) return;


    // Write all regular types including (non-generic) class objects and method tables
    Set<Type> definedGenerics = writeUsedTypes();

    writer.write();
    writer.write("; Recording generic types");
    // Record generic and array classes for later use
    recordGenericClasses(definedGenerics);


    writer.write();
    writer.write("; Mentioned types");
    // Write types that are mentioned but whose internals are never used, allowing them to be
    // written as opaque
    writeMentionedTypes(definedGenerics);

    // Methods for exception handling
    if (Configuration.isWindows()) {
      writer.write("declare i32 @__C_specific_handler(...)");
      writer.write("@__exceptionStorage = external thread_local global " + type(Type.EXCEPTION));
    } else {
      writer.write("declare i32 @__shadow_personality_v0(...)");
      writer.write("declare " + type(Type.EXCEPTION) + " @__shadow_catch(i8* nocapture) nounwind");
      writer.write("declare i32 @llvm.eh.typeid.for(i8*) nounwind readnone");
    }

    // Stack stuff for finally blocks
    writer.write("declare i8* @llvm.localaddress() nounwind readnone");
    writer.write("declare void @llvm.localescape(...) nounwind");
    writer.write("declare i8* @llvm.localrecover(i8*, i8*, i32) nounwind readnone");

    writer.write("declare void @__shadow_throw(" + type(Type.OBJECT) + ") noreturn");
    // memcopy
    writer.write("declare void @llvm.memcpy.p0i8.p0i8.i64(i8*, i8*, i64, i32, i1)");

    // reference counting
    writer.write("declare void @__incrementRef(%shadow.standard..Object*) nounwind");
    writer.write("declare void @__decrementRef(%shadow.standard..Object* %object) nounwind");

    // allocation
    writer.write(
        "declare noalias %shadow.standard..Object* @__allocate(%shadow.standard..Class* %class, %shadow.standard..Object._methods* %methods)");
    writer.write(
        "declare noalias %shadow.standard..Array* @__allocateArray(%shadow.standard..GenericClass* %class, %ulong %longElements, %boolean %nullable)");

    writer.write();

    // defines class and recursively defines inner classes
    writeModuleDefinition(module);
  }

  private String methodList(Iterable<MethodSignature> methods, boolean name) {
    StringBuilder sb = new StringBuilder();
    for (MethodSignature method : methods) {
      sb.append(", ").append(methodType(method));
      if (name) {
        sb.append(' ');
        if (method.isAbstract()) sb.append("null");
        else sb.append(name(method));
      }
    }

    if (!sb.isEmpty()) return sb.substring(2);
    else return sb.toString(); // no methods!
  }

  public void writeStringLiterals() throws ShadowException {
    int stringIndex = 0;

    boolean useRealMethodTable = module != null;

    final String UBYTE_ARRAY_CLASS =
        "bitcast ("
            + type(Type.GENERIC_CLASS)
            + " "
            + classOf(new ArrayType(Type.UBYTE))
            + " to "
            + type(Type.CLASS)
            + ")";

    for (String literal : stringLiterals) {
      byte[] data = literal.getBytes(StandardCharsets.UTF_8);
      StringBuilder sb = new StringBuilder(data.length);
      // Include INT_MAX as -1 at the beginning of the array representation
      // to mark the array as non-gc
      boolean ascii = true;
      for (byte b : data) {
        if (b < 0) ascii = false;
        if (b != '\"' && b >= 0x20 && b < 0x7f) sb.appendCodePoint(b);
        else
          sb.append('\\')
              .append(Character.forDigit((b & 0xff) >>> 4, 16))
              .append(Character.forDigit(b & 0xf, 16));
      }
      writer.write(
          "@_array"
              + stringIndex
              + " = private unnamed_addr "
              + "constant {%ulong, "
              + type(Type.CLASS)
              + ", "
              + (useRealMethodTable ? methodTableType(Type.ARRAY) : type(Type.METHOD_TABLE))
              + ", %long, ["
              + data.length
              + " x "
              + type(Type.UBYTE)
              + "]} { %ulong -1, "
              + typeText(Type.CLASS, UBYTE_ARRAY_CLASS)
              + ", "
              + (useRealMethodTable ? methodTableType(Type.ARRAY) : type(Type.METHOD_TABLE))
              + " "
              + methodTable(Type.ARRAY)
              + ", "
              + typeLiteral((long) data.length)
              + ", ["
              + data.length
              + " x "
              + type(Type.UBYTE)
              + "] c\""
              + sb
              + "\" }");
      writer.write(
          "@_string"
              + stringIndex
              + " = private unnamed_addr "
              + "constant %"
              + raw(Type.STRING)
              + " { "
              + type(Type.ULONG)
              + " "
              + literal(-1)
              + ", "
              + type(Type.CLASS)
              + " "
              + classOf(Type.STRING)
              + ", "
              + (useRealMethodTable ? methodTableType(Type.STRING) : type(Type.METHOD_TABLE))
              + " "
              + methodTable(Type.STRING)
              + ", "
              + type(Type.ARRAY)
              + " bitcast ("
              + "{%ulong, "
              + type(Type.CLASS)
              + ", "
              + (useRealMethodTable ? methodTableType(Type.ARRAY) : type(Type.METHOD_TABLE))
              + ", %long, ["
              + data.length
              + " x "
              + type(Type.UBYTE)
              + "]}* "
              + "@_array"
              + stringIndex
              + " to "
              + type(Type.ARRAY)
              + "), "
              + typeLiteral(ascii)
              + " }");
      stringIndex++;
    }
  }

  @Override
  public void endFile(TACModule module) throws ShadowException {
    if (module.getType() instanceof AttributeType) return;

    // Print only the (mostly private) methods that are called directly
    for (MethodSignature method : usedSignatures) writer.write("declare " + methodToString(method));
    writer.write();

    writeStringLiterals();

    if (Configuration.isWindows()) writeExceptions();
  }

  @Override
  public void startMethod(TACMethod method, TACModule module) throws ShadowException {

    MethodSignature signature = method.getSignature();
    if (module.getType() instanceof InterfaceType
        || module.getType() instanceof AttributeType
        || signature.isAbstract()) {
      skipMethod = true;
    } else if (signature.isImport()) {
      writer.write("declare " + methodToString(method));
      writer.write();
      skipMethod = true;
    } else {
      SequenceType parameters =
          signature.getSignatureWithoutTypeArguments().getFullParameterTypes();
      tempCounter = parameters.size();
      writer.write(
          "define "
              + methodToString(method)
              + (signature.getNode().getParent() == null && (signature.isGet() || signature.isSet())
                  ? " alwaysinline "
                  : "")
              + (signature.isWrapper() ? " unnamed_addr" : "")
              + " personality i32 (...)* "
              + (Configuration.isWindows()
                  ? "@__C_specific_handler {"
                  : "@__shadow_personality_v0 {")); // Because of GC, every function has a
      // personality function
      writer.indent();
    }
  }

  private static String name(TACVariable variable) {
    return '%' + variable.getName();
  }

  public static String name(TACFinallyFunction function) {
    return '@' + "_finally" + function.getNumber() + function.getSignature().getMangledName();
  }

  @Override
  public void walk(TACNode nodes) throws ShadowException {
    if (!skipMethod) super.walk(nodes);
  }

  @Override
  public void endMethod(TACMethod method, TACModule module) throws ShadowException {

    if (!skipMethod) {
      writer.outdent();
      writer.write('}');
      writer.write();

      for (TACFinallyFunction function : method.getFinallyFunctions()) {
        startFinallyFunction(function);
        walk(function.getNode());
        endFinallyFunction();
      }
    }
    skipMethod = false;
  }

  public void startFinallyFunction(TACFinallyFunction function) throws ShadowException {
    tempCounter = 1;
    // writer.write("define internal void " + name(function) + "(i8* %0) \"frame-pointer\"=\"none\""
    // +
    writer.write(
        "define internal void "
            + name(function)
            + "(i8*)"
            + " personality i32 (...)* "
            + (Configuration.isWindows()
                ? "@__C_specific_handler {"
                : "@__shadow_personality_v0 {"));
    writer.indent();
  }

  public void endFinallyFunction() throws ShadowException {
    writer.outdent();
    writer.write('}');
    writer.write();
  }

  @Override
  public void visit(TACLongToPointer node) throws ShadowException {
    writer.write(
        nextTemp(node)
            + " = inttoptr "
            + type(Type.ULONG)
            + " "
            + symbol(node.getOperand(0))
            + " to "
            + type(node.getType()));
  }

  @Override
  public void visit(TACPointerToLong node) throws ShadowException {
    writer.write(
        nextTemp(node)
            + " = ptrtoint "
            + typeSymbol(node.getOperand(0))
            + " to "
            + type(Type.ULONG));
  }

  @Override
  public void visit(TACCopyMemory node) throws ShadowException {
    TACOperand destinationNode = node.getDestination();
    TACOperand sourceNode = node.getSource();
    TACOperand size = node.getSize();

    if (node.isArray()) {
      Type destinationArrayType = destinationNode.getType();
      if (destinationArrayType instanceof ArrayType)
        destinationArrayType = ((ArrayType) destinationArrayType).convertToGeneric();

      Type sourceArrayType = sourceNode.getType();
      if (sourceArrayType instanceof ArrayType)
        sourceArrayType = ((ArrayType) sourceArrayType).convertToGeneric();

      writer.write(
          nextTemp()
              + " = getelementptr inbounds %"
              + raw(destinationArrayType)
              + ", "
              + typeSymbol(destinationNode)
              + ", "
              + typeLiteral(1));

      writer.write(
          nextTemp()
              + " = getelementptr inbounds %"
              + raw(sourceArrayType)
              + ", "
              + typeSymbol(sourceNode)
              + ", "
              + typeLiteral(1));

      // destination
      writer.write(
          nextTemp() + " = bitcast " + typeText(destinationArrayType, temp(2)) + " to i8*");

      // source
      writer.write(nextTemp() + " = bitcast " + typeText(sourceArrayType, temp(2)) + " to i8*");

      writer.write(
          "call void @llvm.memcpy.p0i8.p0i8.i64(i8* "
              + temp(1)
              + ", i8* "
              + temp(0)
              + ", "
              + typeSymbol(size)
              + ", i32 1, i1 0)");
    } else {
      String destination = typeSymbol(destinationNode);
      String source = typeSymbol(sourceNode);

      writer.write(nextTemp() + " = bitcast " + destination + " to i8*");
      writer.write(nextTemp() + " = bitcast " + source + " to i8*");

      // objects and arrays (used in copy() and freeze()) have an 8 byte reference count that should
      // *not* be copied
      writer.write(nextTemp() + " = getelementptr i8, i8* " + temp(2) + ", i32 8"); // destination
      writer.write(nextTemp() + " = getelementptr i8, i8* " + temp(2) + ", i32 8"); // source
      writer.write(nextTemp() + " = sub i64 " + symbol(size) + ", 8");
      writer.write(
          "call void @llvm.memcpy.p0i8.p0i8.i64(i8* "
              + temp(2)
              + ", i8* "
              + temp(1)
              + ", i64 "
              + temp(0)
              + ", i32 1, i1 0)");
    }
  }

  private String funcletData(TACCatchPad catchPad) {
    if (catchPad == null) return "";

    return " [ \"funclet\"(token " + catchPad.getToken() + ")]";
  }

  @Override
  public void visit(TACMethodPointer node) throws ShadowException {
    node.setData(symbol(node.getPointer()));
  }

  @Override
  public void visit(TACMethodName node) throws ShadowException {
    if (node.getOuterType() instanceof InterfaceType) {
      writer.write(
          nextTemp()
              + " = extractvalue "
              + type(node.getOuterType())
              + " "
              + symbol(node.getPrefix())
              + ", 0");
      writer.write(
          nextTemp()
              + " = getelementptr "
              + methodTableType(node.getOuterType(), false)
              + ", "
              + methodTableType(node.getOuterType())
              + " "
              + temp(1)
              + ", i32 0, i32 "
              + node.getIndex());
      String back1 = temp(0);
      writer.write(
          nextTemp(node) + " = load " + methodType(node) + ", " + methodType(node) + "* " + back1);
    } else if (node.hasPrefix()
        &&
        // Devirtualization conditions below
        !node.getOuterType().isPrimitive()
        && !node.getOuterType().getModifiers().isLocked()
        && !node.getType().getModifiers().isLocked()
        && !(node.getOuterType() instanceof SingletonType)
        && !node.getType().getModifiers().isPrivate()
        && !node.isSuper()) {
      writer.write(
          nextTemp()
              + " = getelementptr inbounds %"
              + raw(node.getPrefix().getType())
              + ", "
              + typeSymbol(node.getPrefix())
              + ", i32 0, i32 2");

      writer.write(
          nextTemp()
              + " = load "
              + methodTableType(node.getPrefix().getType())
              + ", "
              + methodTableType(node.getPrefix().getType())
              + "* "
              + temp(1));

      writer.write(
          nextTemp()
              + " = getelementptr inbounds "
              + methodTableType(node.getPrefix().getType(), false)
              + ", "
              + methodTableType(node.getPrefix().getType())
              + " "
              + temp(1)
              + ", i32 0, i32 "
              + node.getIndex()); // may need to + 1 to the node.getIndex() if a parent method table
      // is added

      String back1 = temp(0);
      writer.write(
          nextTemp(node) + " = load " + methodType(node) + ", " + methodType(node) + "* " + back1);
    } else {
      node.setData(name(node));
      MethodSignature signature = node.getSignature();
      if (!module.getType().encloses(signature.getOuter()) && !signature.isWrapper())
        usedSignatures.add(signature.getSignatureWithoutTypeArguments());
    }
  }

  @Override
  public void visit(TACClass node) throws ShadowException {
    node.setData(node.getClassData().getData());
  }

  @Override
  public void visit(TACSequence node) throws ShadowException {
    String current = "undef";
    for (int i = 0; i < node.size(); i++) {
      writer.write(
          nextTemp()
              + " = insertvalue "
              + typeText(node, current)
              + ", "
              + typeSymbol(node.get(i))
              + ", "
              + i);
      current = temp(0);
    }
    node.setData(current);
  }

  @Override
  public void visit(TACSequenceElement node) throws ShadowException {
    writer.write(
        nextTemp(node)
            + " = extractvalue "
            + typeSymbol(node.getOperand(0))
            + ", "
            + node.getIndex());
  }

  @SuppressWarnings("DuplicateExpressions")
  @Override
  public void visit(TACCast node) throws ShadowException {
    TACOperand value = node.getUpdatedValue();
    // no pre-computation done
    if (value == null) {
      TACOperand source = node.getOperand(0);
      Type destType = node.getType();
      Type srcType = source.getType();
      String back1, back2;
      String srcTypeName;
      String destTypeName;

      switch (node.getKind()) {
        case INTERFACE_TO_OBJECT -> writer.write(nextTemp(node) + " = extractvalue " + typeSymbol(source) + ", 1");
        case ITEM_TO_SEQUENCE -> writer.write(
                nextTemp(node)
                        + " = insertvalue "
                        + type(node)
                        + " undef, "
                        + typeSymbol(node.getOperand(1))
                        + ", 0");
        case NULL_TO_INTERFACE -> writer.write(
                nextTemp(node)
                        + " = insertvalue "
                        + type(node)
                        + " zeroinitializer, "
                        + type(Type.OBJECT)
                        + " null, 1");
        case OBJECT_TO_INTERFACE -> {
          // operand 1 is the interface method table
          writer.write(
                  nextTemp()
                          + " = bitcast "
                          + typeSymbol(node.getOperand(1))
                          + " to "
                          + methodTableType(destType));
          writer.write(
                  nextTemp()
                          + " = insertvalue "
                          + type(destType)
                          + " undef, "
                          + methodTableType(destType)
                          + " "
                          + temp(1)
                          + ", 0");
          writer.write(
                  nextTemp() + " = bitcast " + typeSymbol(source) + " to " + type(Type.OBJECT));
          back1 = temp(0);
          back2 = temp(1);
          writer.write(
                  nextTemp(node)
                          + " = insertvalue "
                          + typeText(destType, back2)
                          + ", "
                          + typeText(Type.OBJECT, back1)
                          + ", 1");
        }
        case OBJECT_TO_ARRAY, OBJECT_TO_OBJECT -> {
          srcTypeName = type(srcType, true); // nullable takes care of primitives in object form
          destTypeName = type(destType, true);
          writer.write(
                  nextTemp(node)
                          + " = bitcast "
                          + srcTypeName
                          + ' '
                          + symbol(source)
                          + " to "
                          + destTypeName);
        }
        case OBJECT_TO_PRIMITIVE -> {
          writer.write(
                  nextTemp() + " = bitcast " + typeSymbol(source) + " to %" + raw(destType) + "*");
          writer.write(
                  nextTemp()
                          + " = getelementptr inbounds %"
                          + raw(destType)
                          + ", %"
                          + raw(destType)
                          + "* "
                          + temp(1)
                          + ", i32 0, i32 3");
          back1 = temp(0);
          writer.write(
                  nextTemp(node)
                          + " = load "
                          + type(destType)
                          + ", "
                          + typeText(destType, back1, true));
        }
        case PRIMITIVE_TO_OBJECT -> {
          writer.write(
                  nextTemp()
                          + " = call noalias "
                          + type(Type.OBJECT)
                          + " @__allocate("
                          + type(Type.CLASS)
                          + ' '
                          + classOf(srcType)
                          + ", "
                          + methodTableType(Type.OBJECT)
                          + " bitcast("
                          + methodTableType(srcType)
                          + " "
                          + methodTable(srcType)
                          + " to "
                          + methodTableType(Type.OBJECT)
                          + ")"
                          + ")");
          back1 = temp(0);
          String result = nextTemp(node);
          writer.write(
                  result + " = bitcast " + typeText(Type.OBJECT, back1) + " to %" + raw(srcType) + "*");
          writer.write(
                  nextTemp()
                          + " = getelementptr inbounds %"
                          + raw(srcType)
                          + ", %"
                          + raw(srcType)
                          + "* "
                          + result
                          + ", i32 0, i32 3");
          writer.write("store " + typeSymbol(source) + ", " + typeText(srcType, temp(0), true));
        }
        case PRIMITIVE_TO_PRIMITIVE -> {
          String instruction;
          int srcWidth = Type.getWidth(source), destWidth = Type.getWidth(node);
          srcTypeName = type(srcType);
          destTypeName = type(destType);
          if (srcType.isFloating()) {
            if (destType.isFloating())
              instruction =
                      srcWidth > destWidth ? "fptrunc" : (srcWidth < destWidth ? "fpext" : "bitcast");
            else if (destType.isSigned()) instruction = "fptosi";
            else instruction = "fptoui";
          } else if (srcType.isSigned()) {
            if (destType.isFloating()) instruction = "sitofp";
            else if (destType.isSigned())
              instruction =
                      srcWidth > destWidth ? "trunc" : (srcWidth < destWidth ? "sext" : "bitcast");
            else
              instruction =
                      srcWidth > destWidth ? "trunc" : (srcWidth < destWidth ? "zext" : "bitcast");
          } else { //  srcType.isUnsigned() must be true
            if (destType.isFloating()) instruction = "uitofp";
            else
              instruction =
                      srcWidth > destWidth ? "trunc" : (srcWidth < destWidth ? "zext" : "bitcast");
          }
          writer.write(
                  nextTemp(node)
                          + " = "
                          + instruction
                          + ' '
                          + srcTypeName
                          + ' '
                          + symbol(source)
                          + " to "
                          + destTypeName);
        }
        case SEQUENCE_TO_ITEM -> writer.write(nextTemp(node) + " = extractvalue " + typeSymbol(source) + ", 0");
        case SEQUENCE_TO_SEQUENCE -> {
          String current = "undef";
          String location;
          int index = 0;
          for (int i = 0; i < node.getNumOperands(); i++) {
            if (i == node.getNumOperands() - 1) location = nextTemp(node);
            else location = nextTemp();
            writer.write(
                    location
                            + " = insertvalue "
                            + typeText(node, current)
                            + ", "
                            + typeSymbol(node.getOperand(i))
                            + ", "
                            + index);
            current = location;
            index++;
          }
        }
      }
    } else
      // unusual case when casting from one primitive constant to another
      node.setData(value.getData());
  }

  @Override
  public void visit(TACNewObject node) throws ShadowException {
    Type type = node.getClassType();

    TACOperand _class = node.getClassData();
    TACOperand methods = node.getMethodTable();

    // this is pretty ugly, but if we retrieved a generic type parameter's method table, it'll be
    // stored as MethodTable*, not an Object._methods*
    if (type instanceof TypeParameter parameter) {
      writer.write(
          nextTemp()
              + " = bitcast "
              + type(Type.METHOD_TABLE)
              + " "
              + symbol(methods)
              + " to "
              + methodTableType(parameter.getClassBound()));
      // Any other method table will be of the correct type but needs a cast to Object._methods* for
      // compatibility with allocate()
    } else
      writer.write(
          nextTemp()
              + " = bitcast "
              + methodTableType(type.getTypeWithoutTypeArguments())
              + " "
              + symbol(methods)
              + " to "
              + methodTableType(Type.OBJECT));
    String back1 = temp(0);
    writer.write(
        nextTemp(node)
            + " = call noalias "
            + type(Type.OBJECT)
            + " @__allocate("
            + type(Type.CLASS)
            + " "
            + symbol(_class)
            + ", "
            + methodTableType(Type.OBJECT)
            + " "
            + back1
            + " )");
  }

  @Override
  public void visit(TACNewArray node) throws ShadowException {
    String allocationClass = typeSymbol(node.getAllocationClass());
    ArrayType type = node.getType();
    if (type.isNullable()) {
      writer.write(
          nextTemp()
              + " = call noalias "
              + type(Type.ARRAY)
              + " @__allocateArray("
              + allocationClass
              + ", "
              + typeSymbol(node.getSize())
              + ", "
              + typeText(Type.BOOLEAN, "true")
              + ')');

      String last = temp(0);
      writer.write(
          nextTemp(node)
              + " = bitcast "
              + typeText(Type.ARRAY, last)
              + " to "
              + type(Type.ARRAY_NULLABLE));
    } else
      writer.write(
          nextTemp(node)
              + " = call noalias "
              + type(Type.ARRAY)
              + " @__allocateArray("
              + allocationClass
              + ", "
              + typeSymbol(node.getSize())
              + ", "
              + typeText(Type.BOOLEAN, "false")
              + ')');
  }

  @Override
  public void visit(TACUnary node) throws ShadowException {
    TACOperand value = node.getUpdatedValue();
    // no precomputation done
    if (value == null) {
      switch (node.getOperation()) {
        case "-" -> {
          if (node.getType().isFloating()) visitUnary(node, "fsub", "-0.0");
          else visitUnary(node, "sub", "0");
        }
        case "~", "!" -> visitUnary(node, "xor", "-1");
      }
    } else node.setData(value.getData());
  }

  private void visitUnary(TACUnary node, String instruction, String first) throws ShadowException {

    writer.write(
        nextTemp(node)
            + " = "
            + instruction
            + ' '
            + typeText(node, first)
            + ", "
            + symbol(node.getOperand()));
  }

  @Override
  public void visit(TACAllocateVariable node) throws ShadowException {
    TACVariable local = node.getVariable();
    writer.write(name(local) + " = alloca " + type(local));
    // Only _exception is expected to be a SequenceType
    if (!(local.getType() instanceof SequenceType)
        && (!local.getType().isPrimitive() || local.getModifiers().isNullable()))
      writer.write(
          "store "
              + type(local)
              + " "
              + literal(new ShadowNull(local.getType()))
              + ", "
              + typeText(local, name(local), true));
  }

  @Override
  public void visit(TACChangeReferenceCount node) throws ShadowException {
    ModifiedType reference;
    TACFieldRef field = null;
    TACVariable variable = null;

    String name;

    if (node.isField()) reference = field = node.getField();
    else reference = variable = node.getVariable();

    if (node.isIncrement()) {
      if (node.isField()) {
        assert field != null;
        writer.write(
            nextTemp()
                + " = getelementptr inbounds "
                + "%"
                + raw(field.getPrefix().getType())
                + ", "
                + typeSymbol(field.getPrefix())
                + ", i32 0, i32 "
                + (field.getIndex()));
        writer.write(nextTemp() + " = load " + type(field) + ", " + typeText(field, temp(1), true));
        name = temp(0);
      } else {
        assert variable != null;
        if (variable.getOriginalName().equals("this")) // special case for this
        name = "%0";
        else {
          writer.write(
              nextTemp()
                  + " = load "
                  + type(variable)
                  + ", "
                  + typeText(variable, name(variable), true));
          name = temp(0);
        }
      }

      if (reference.getType() instanceof InterfaceType)
        writer.write(nextTemp() + " = extractvalue " + typeText(reference, name) + ", 1");
      else
        writer.write(
            nextTemp() + " = bitcast " + typeText(reference, name) + " to " + type(Type.OBJECT));

      // same increment for both cases
      writer.write("call void @__incrementRef(" + typeText(Type.OBJECT, temp(0)) + ") nounwind");
    } else {
      if (node.isField()) {
        assert field != null;
        writer.write(
            nextTemp()
                + " = getelementptr inbounds "
                + "%"
                + raw(field.getPrefix().getType())
                + ", "
                + typeSymbol(field.getPrefix())
                + ", i32 0, i32 "
                + (field.getIndex()));
        writer.write(nextTemp() + " = load " + type(field) + ", " + typeText(field, temp(1), true));
        name = temp(0);
      } else {
        assert variable != null;
        if (variable.getOriginalName().equals("this")) name = "%0";
        else {
          writer.write(
              nextTemp()
                  + " = load "
                  + type(variable)
                  + ", "
                  + typeText(variable, name(variable), true));
          name = temp(0);
        }
      }

      if (reference.getType() instanceof InterfaceType)
        writer.write(nextTemp() + " = extractvalue " + typeText(reference, name) + ", 1");
      else
        writer.write(
            nextTemp() + " = bitcast " + typeText(reference, name) + " to " + type(Type.OBJECT));

      // same decrement for these two cases
      writer.write("call void @__decrementRef(" + typeText(Type.OBJECT, temp(0)) + ") nounwind");
    }
  }

  @Override
  public void visit(TACBinary node) throws ShadowException {
    TACOperand value = node.getUpdatedValue();
    // no precomputation done
    if (value == null) {
      switch (node.getOperation()) {
        case "+" -> visitUnsignedOperation(node, "add");
        case "-" -> visitUnsignedOperation(node, "sub");
        case "*" -> visitUnsignedOperation(node, "mul");
        case "/" -> visitSignedOperation(node, "div");
        case "%" -> visitSignedOperation(node, "rem");
        case "or", "|" -> visitNormalOperation(node, "or");
        case "xor", "^" -> visitNormalOperation(node, "xor");
        case "and", "&" -> visitNormalOperation(node, "and");
        case "<<" -> visitNormalOperation(node, "shl");
        case ">>" -> visitShiftOperation(node);
        case "<<<" -> visitRotateLeft(node);
        case ">>>" -> visitRotateRight(node);
        case "==" -> visitEqualityOperation(node, "eq");
        case "!=" -> visitEqualityOperation(node, "ne");
        case "<" -> visitRelationalOperation(node, "lt");
        case ">" -> visitRelationalOperation(node, "gt");
        case "<=" -> visitRelationalOperation(node, "le");
        case ">=" -> visitRelationalOperation(node, "ge");
        case "===" -> visitReferenceEquality(node);
        default -> throw new UnsupportedOperationException(
                "Binary operation " + node.getOperation() + " not supported on current types");
      }
    } else node.setData(value.getData());
  }

  private void visitReferenceEquality(TACBinary node) throws ShadowException {
    Type type = node.getOperand(0).getType();

    if (type instanceof InterfaceType) {
      writer.write(nextTemp() + " = extractvalue " + typeSymbol(node.getOperand(0)) + ", 1");
      writer.write(nextTemp() + " = extractvalue " + typeSymbol(node.getOperand(0)) + ", 1");
      String back1 = temp(0);
      String back2 = temp(1);
      writer.write(nextTemp(node) + " = icmp eq" + type(Type.OBJECT) + back2 + ", " + back1);
    } else {
      String op = type.isFloating() ? "fcmp oeq " : "icmp eq ";
      writer.write(
          nextTemp(node)
              + " = "
              + op
              + typeSymbol(node.getOperand(0))
              + ", "
              + symbol(node.getOperand(1)));
    }
  }

  private void visitUnsignedOperation(TACBinary node, String instruction) throws ShadowException {
    visitOperation(node, "", "", "f", instruction);
  }

  private void visitSignedOperation(TACBinary node, String instruction) throws ShadowException {
    visitOperation(node, "s", "u", "f", instruction);
  }

  private void visitNormalOperation(TACBinary node, String instruction) throws ShadowException {
    visitOperation(node, "", "", "", instruction);
  }

  private void visitShiftOperation(TACBinary node) throws ShadowException {
    visitOperation(node, "a", "l", "", "shr");
  }

  private void visitEqualityOperation(TACBinary node, String instruction) throws ShadowException {
    visitOperation(node, "icmp ", "icmp ", "fcmp o", instruction);
  }

  private void visitRelationalOperation(TACBinary node, String instruction) throws ShadowException {
    visitOperation(node, "icmp s", "icmp u", "fcmp o", instruction);
  }

  private void visitOperation(
      TACBinary node, String signed, String unsigned, String floatingPoint, String instruction)
      throws ShadowException {
    StringBuilder sb = new StringBuilder(nextTemp(node)).append(" = ");
    Type type = node.getFirst().getType();
    if (type.isIntegral() || !type.isPrimitive())
      if (type.isSigned()) sb.append(signed);
      else sb.append(unsigned);
    else sb.append(floatingPoint);
    writer.write(
        sb.append(instruction)
            .append(' ')
            .append(typeSymbol(type, node.getFirst()))
            .append(", ")
            .append(symbol(node.getSecond()))
            .toString());
  }

  private void visitRotateLeft(TACBinary node) throws ShadowException {
    visitRotate(node, "shl", "lshr");
  }

  private void visitRotateRight(TACBinary node) throws ShadowException {
    visitRotate(node, "lshr", "shl");
  }

  private void visitRotate(TACBinary node, String dir, String otherDir) throws ShadowException {
    String type = type(node),
        _type_ = ' ' + type + ' ',
        first = symbol(node.getFirst()),
        second = symbol(node.getSecond());
    writer.write(nextTemp() + " = " + dir + _type_ + first + ", " + second);
    writer.write(
        nextTemp()
            + " = sub"
            + _type_
            + "mul ("
            + type
            + " ptrtoint ("
            + type
            + "* getelementptr ("
            + type
            + ", "
            + type
            + "* null, i32 1) to "
            + type
            + "),"
            + _type_
            + "8), "
            + second);
    writer.write(nextTemp() + " = " + otherDir + _type_ + first + ", " + temp(1));

    String back1 = temp(0);
    String back2 = temp(1);
    writer.write(nextTemp(node) + " = or" + _type_ + back1 + ", " + back2);
  }

  @Override
  public void visit(TACLocalStore node) throws ShadowException {
    // If not garbage collected (or used in a finally), the "store" only happens in SSA data flow
    TACVariable variable = node.getVariable();
    if (node.isGarbageCollected()) {
      // initial parameter stores never have decrements
      // sometimes they are incremented, if a value is later stored into the same parameter name
      if (node.getValue() instanceof TACParameter parameter) {
        gcObjectStore(
            name(variable),
            variable.getType(),
            node.getValue(),
            parameter.isIncrement(),
            false,
            node.getCatchPad());
      } else if (variable.getType() instanceof InterfaceType)
        gcInterfaceStore(
            name(variable),
            (InterfaceType) variable.getType(),
            node.getValue(),
            node.isIncrementReference(),
            node.isDecrementReference(),
            node.getCatchPad());
      else
        gcObjectStore(
            name(variable),
            variable.getType(),
            node.getValue(),
            node.isIncrementReference() && node.isGarbageCollected(),
            node.isDecrementReference() && node.isGarbageCollected(),
            node.getCatchPad());
    } else if (variable.isFinallyVariable()) // And not garbage collected
    writer.write(
          "store " + typeSymbol(node.getValue()) + ", " + type(variable) + "* " + name(variable));
  }

  @Override
  public void visit(TACLocalEscape node) throws ShadowException {
    StringBuilder sb = new StringBuilder("call void (...) @llvm.localescape(");
    List<TACVariable> variables = node.getEscapedVariables();
    boolean first = true;
    for (TACVariable variable : variables) {
      if (first) first = false;
      else sb.append(", ");
      sb.append(type(variable)).append("* ").append(name(variable));
    }
    sb.append(")");

    writer.write(sb.toString());
  }

  @Override
  public void visit(TACLocalRecover node) throws ShadowException {
    TACVariable variable = node.getVariable();
    MethodSignature signature = node.getMethod().getSignature();

    writer.write(
        nextTemp()
            + " = call i8* @llvm.localrecover(i8* bitcast ("
            + methodToString(signature, false, true)
            + "* "
            + name(signature)
            + " to i8*), i8* %0, i32 "
            + node.getIndex()
            + ")");
    writer.write(name(variable) + " = bitcast i8* " + temp(0) + " to " + type(variable) + "*");
  }

  @Override
  public void visit(TACLoad node) throws ShadowException {
    TACReference reference = node.getReference();
    String back1;

    if (reference instanceof TACSingletonRef singleton) {
      writer.write(
          nextTemp(node)
              + " = load "
              + type(singleton)
              + ", "
              + typeText(singleton, '@' + raw(singleton.getType(), ".singleton"), true));

    } else if (reference instanceof TACArrayRef arrayRef) {
      // has type of result
      ArrayType arrayType = (ArrayType) arrayRef.getArray().getType();
      ClassType genericArray = arrayType.convertToGeneric().getTypeWithoutTypeArguments();
      writer.write(
          nextTemp()
              + " = getelementptr inbounds %"
              + raw(genericArray)
              + ", "
              + typeSymbol(arrayRef.getArray())
              + ", "
              + typeLiteral(1));
      writer.write(
          nextTemp() + " = bitcast " + typeTemp(arrayType, 1) + " to " + type(arrayRef) + "*");
      writer.write(
          nextTemp()
              + " = getelementptr inbounds "
              + type(arrayRef)
              + ", "
              + typeText(arrayRef, temp(1), true)
              + ", "
              + typeSymbol(arrayRef.getIndex()));
      back1 = temp(0);
      writer.write(
          nextTemp(node) + " = load " + type(arrayRef) + ", " + typeText(arrayRef, back1, true));
    } else if (reference instanceof TACFieldRef fieldRef) {
      Type prefixType = fieldRef.getPrefix().getType();
      if (prefixType instanceof ArrayType) prefixType = ((ArrayType) prefixType).convertToGeneric();
      else if (prefixType instanceof MethodType) prefixType = Type.METHOD;
      writer.write(
          nextTemp()
              + " = getelementptr inbounds "
              + "%"
              + raw(prefixType)
              + ", "
              + type(prefixType)
              + " "
              + symbol(fieldRef.getPrefix())
              + ", i32 0, i32 "
              + (fieldRef.getIndex()));
      back1 = temp(0);
      writer.write(
          nextTemp(node) + " = load " + type(fieldRef) + ", " + typeText(fieldRef, back1, true));
    }
    /*
    else if( reference instanceof TACConstantRef ) {
    	TACConstantRef constant = (TACConstantRef)reference;
    	writer.write(nextTemp(node) + " = load " + type(constant) + ", " +
    			typeText(constant, name(constant), true));
    	if( !module.getType().encloses(constant.getPrefixType()) )
    		usedConstants.add(constant);
    }
    */
    else if (reference instanceof TACGlobalRef global) {
      writer.write(
          nextTemp(node)
              + " = load "
              + type(global)
              + ", "
              + type(global)
              + "* "
              + global.getName());
    }
  }

  @Override
  public void visit(TACStore node) throws ShadowException {
    TACReference reference = node.getReference();

    if (reference instanceof TACSingletonRef singleton) {
      // singletons are never GC
      writer.write(
          "store "
              + typeSymbol(node.getValue())
              + ", "
              + typeText(singleton, '@' + raw(singleton.getType(), ".singleton"), true));
    } else if (reference instanceof TACArrayRef arrayRef) {
      // has type of result
      ArrayType arrayType = (ArrayType) arrayRef.getArray().getType();
      ClassType genericArray = arrayType.convertToGeneric().getTypeWithoutTypeArguments();
      writer.write(
          nextTemp()
              + " = getelementptr inbounds %"
              + raw(genericArray)
              + ", "
              + typeSymbol(arrayRef.getArray())
              + ", "
              + typeLiteral(1));
      writer.write(
          nextTemp() + " = bitcast " + typeTemp(arrayType, 1) + " to " + type(arrayRef) + "*");
      writer.write(
          nextTemp()
              + " = getelementptr inbounds "
              + type(arrayRef)
              + ", "
              + typeText(arrayRef, temp(1), true)
              + ", "
              + typeSymbol(arrayRef.getIndex()));

      if (node.isGarbageCollected()) {
        if (arrayRef.getType() instanceof InterfaceType)
          gcInterfaceStore(
              temp(0),
              (InterfaceType) arrayRef.getType(),
              node.getValue(),
              node.isIncrementReference(),
              node.isDecrementReference(),
              null);
        // regular variable
        else
          gcObjectStore(
              temp(0),
              arrayRef.getType(),
              node.getValue(),
              node.isIncrementReference(),
              node.isDecrementReference(),
              null);
      } else
        writer.write(
            "store " + typeSymbol(node.getValue()) + ", " + typeText(arrayRef, temp(0), true));
    } else if (reference instanceof TACFieldRef fieldRef) {

      writer.write(
          nextTemp()
              + " = getelementptr inbounds "
              + "%"
              + raw(fieldRef.getPrefix().getType())
              + ", "
              + typeSymbol(fieldRef.getPrefix())
              + ", i32 0, i32 "
              + (fieldRef.getIndex()));

      if (node.isGarbageCollected()) {
        if (fieldRef.getType() instanceof InterfaceType)
          gcInterfaceStore(
              temp(0),
              (InterfaceType) fieldRef.getType(),
              node.getValue(),
              node.isIncrementReference(),
              node.isDecrementReference(),
              null);
        // regular variable
        else
          gcObjectStore(
              temp(0),
              fieldRef.getType(),
              node.getValue(),
              node.isIncrementReference(),
              node.isDecrementReference(),
              null);
      } else
        writer.write(
            "store "
                + typeSymbol(node.getValue())
                + ", "
                + typeText(fieldRef.getType(), temp(0), true));
    }
  }

  private void gcObjectStore(
      String destination,
      Type type,
      TACOperand value,
      boolean increment,
      boolean decrement,
      TACCatchPad catchPad)
      throws ShadowException {

    if (increment && !value.isNull()) {
      writer.write(nextTemp() + " = bitcast " + typeSymbol(value) + " to " + type(Type.OBJECT));
      writer.write("call void @__incrementRef(" + typeText(Type.OBJECT, temp(0)) + ") nounwind");
    }

    // decrement old value in variable
    // gc type is always nullable, to catch the wrapper versions of primitives
    if (decrement) {
      writer.write(
          nextTemp()
              + " = load "
              + type(type, true)
              + ", "
              + type(type, true)
              + "* "
              + destination);
      writer.write(
          nextTemp()
              + " = bitcast "
              + type(type, true)
              + " "
              + temp(1)
              + " to "
              + type(Type.OBJECT));
      writer.write(
          "call void @__decrementRef("
              + typeText(Type.OBJECT, temp(0))
              + ") nounwind"
              + funcletData(catchPad));
    }
    // then store new value
    writer.write("store " + typeSymbol(value) + ", " + type(type, true) + "* " + destination);
  }

  private void gcInterfaceStore(
      String destination,
      InterfaceType type,
      TACOperand value,
      boolean increment,
      boolean decrement,
      TACCatchPad catchPad)
      throws ShadowException {
    if (increment) {
      // increment the Object* reference
      writer.write(nextTemp() + " = extractvalue " + typeSymbol(value) + ", 1");
      writer.write(
          "call void @__incrementRef("
              + typeText(Type.OBJECT, temp(0))
              + ") nounwind"
              + funcletData(catchPad));
    }

    // decrement the old one
    if (decrement) {
      writer.write(
          nextTemp()
              + " = getelementptr inbounds "
              + type(type)
              + ", "
              + typeText(type, destination, true)
              + ", i32 0, i32 1");
      writer.write(
          nextTemp()
              + " = load "
              + type(Type.OBJECT)
              + ", "
              + typeText(Type.OBJECT, temp(1), true));
      writer.write(
          "call void @__decrementRef("
              + typeText(Type.OBJECT, temp(0))
              + ") nounwind"
              + funcletData(catchPad));
    }

    // store the whole thing after decrement
    writer.write("store " + typeSymbol(value) + ", " + typeText(type, destination, true));
  }

  @Override
  public void visit(TACLocalLoad node) throws ShadowException {
    TACVariable variable = node.getVariable();
    if (node.isGarbageCollected() || variable.isFinallyVariable())
      writer.write(
          nextTemp(node)
              + " = load "
              + type(variable)
              + ", "
              + type(variable)
              + "* "
              + name(variable));
    else {
      TACOperand store = node.getPreviousStore();
      if (store == null) {
        TACNode previous = node.getPrevious();
        while (!(previous instanceof TACLabel)) previous = previous.getPrevious();

        throw new IllegalArgumentException("No storage before load");
      } else node.setData(store.getData());
    }
  }

  @Override
  public void visit(TACPhi node) throws ShadowException {

    // Garbage collected phi variable is not needed since all of those loads are used directly
    if (!node.isGarbageCollected() && !node.getVariable().isFinallyVariable()) {
      Map<TACLabel, TACOperand> values = node.getPreviousStores();
      if (values.size() > 1) {
        StringBuilder sb =
            new StringBuilder(name(node)).append(" = phi ").append(type(node)).append(" ");
        for (Map.Entry<TACLabel, TACOperand> entry : values.entrySet())
          sb.append("[ ")
              .append(symbol(entry.getValue()))
              .append(", ")
              .append(symbol(entry.getKey()))
              .append(" ],");
        writer.write(sb.deleteCharAt(sb.length() - 1).toString());
      } else if (values.size() == 1) node.setData(values.values().iterator().next().getData());
    }
  }

  @Override
  public void visit(TACBranch node) throws ShadowException {
    if (node.isConditional())
      writer.write(
          "br "
              + typeSymbol(node.getCondition())
              + ", label "
              + symbol(node.getTrueLabel())
              + ", label "
              + symbol(node.getFalseLabel()));
    else if (node.isDirect()) writer.write("br label " + symbol(node.getLabel()));
    else if (node.isIndirect()) {
      StringBuilder sb =
          new StringBuilder("indirectbr ").append(typeSymbol(node.getPhi())).append(", [ ");
      TACPhi phi = node.getPhi();
      boolean first = true;
      for (TACOperand operand : phi.getPreviousStores().values()) {
        if (first) first = false;
        else sb.append(", ");
        TACLabelAddress address = (TACLabelAddress) operand;
        sb.append("label ").append(symbol(address.getLabel()));
      }
      sb.append(" ]");
      writer.write(sb.toString());
    }
  }

  @Override
  public void visit(TACLabel node) throws ShadowException {
    writer.writeLeft(name(node) + ':');
  }

  @Override
  public void visit(TACCall node) throws ShadowException {
    TACOperand value = node.getUpdatedValue();
    TACLabel unwindLabel = node.getBlock().getUnwind();

    // No precomputation done
    if (value == null) {
      StringBuilder sb = new StringBuilder(unwindLabel != null ? "invoke" : "call").append(' ');
      TACMethodRef method = node.getMethodRef();
      if (method instanceof TACMethodName methodName) {
        sb.append(methodToString(methodName, false)).append(symbol(methodName)).append('(');
      } else if (method instanceof TACMethodPointer methodPointer) {
        sb.append(methodToString(methodPointer)).append(symbol(methodPointer)).append('(');
      }
      boolean first = true;
      for (TACOperand param : node.getParameters())
        if (first) {
          first = false;
          sb.append(typeSymbol(param));
        } else sb.append(", ").append(typeSymbol(param));

      if (!method.getReturnTypes().isEmpty()) sb.insert(0, nextTemp(node) + " = ");

      writer.write(sb.append(')').toString());
      if (unwindLabel != null) {
        writer.indent(2);
        writer.write(
            " to label "
                + symbol(node.getNoExceptionLabel())
                + " unwind label "
                + symbol(unwindLabel));
        writer.outdent(2);
      }
    } else {
      node.setData(value.getData());
      if (unwindLabel != null) writer.write("br label " + symbol(node.getNoExceptionLabel()));
    }
  }

  @Override
  public void visit(TACCallFinallyFunction node) throws ShadowException {
    TACFinallyFunction function = node.getFinallyFunction();

    String funclet = "";
    TACCleanupPad cleanupPad = node.getCleanupPad();
    if (cleanupPad != null) funclet = " [ \"funclet\"(token " + symbol(cleanupPad) + ")]";

    if (function.getOuterFinallyFunction() == null) {
      String address = nextTemp();
      writer.write(address + " = call i8* @llvm.localaddress()" + funclet);
      writer.write("call void " + name(function) + "(i8* " + address + ")" + funclet);
    } else writer.write("call void " + name(function) + "(i8* %0)" + funclet);
  }

  @Override
  public void visit(TACReturn node) throws ShadowException {
    if (node.hasReturnValue()) writer.write("ret " + typeSymbol(node.getReturnValue()));
    else writer.write("ret void");
  }

  @Override
  public void visit(TACThrow node) throws ShadowException {
    writer.write(
        nextTemp() + " = bitcast " + typeSymbol(node.getException()) + " to " + type(Type.OBJECT));
    writer.write("call void @__incrementRef(" + typeText(Type.OBJECT, temp(0)) + ") nounwind");
    TACLabel unwindLabel = node.getBlock().getUnwind();

    writer.write(
        (unwindLabel != null ? "invoke" : "call")
            + " void @__shadow_throw("
            + typeSymbol(node.getException())
            + ") noreturn");
    if (unwindLabel != null) {
      writer.indent(2);
      writer.write(" to label " + nextTemp() + " unwind label " + symbol(unwindLabel));
      writer.outdent(2);
    }

    writer.write("unreachable");
  }

  @Override
  public void visit(TACTypeId node) throws ShadowException {
    writer.write(nextTemp() + " = bitcast " + typeSymbol(node.getOperand()) + " to i8*");
    String back1 = temp(0);
    writer.write(
        nextTemp(node) + " = tail call i32 " + "@llvm.eh.typeid.for(i8* " + back1 + ") nounwind");
  }

  @Override
  public void visit(TACCleanupRet node) throws ShadowException {
    // Unwind
    String unwind;
    TACLabel unwindLabel = node.getUnwind();
    if (unwindLabel != null) unwind = "label " + symbol(unwindLabel);
    else unwind = "to caller";
    writer.write("cleanupret from " + symbol(node.getCleanupPad()) + " unwind " + unwind);
  }

  @Override
  public void visit(TACCleanupPad node) throws ShadowException {
    writer.write(nextTemp(node) + " = cleanuppad within none []");
  }

  @Override
  public void visit(TACCatchPad node) throws ShadowException {
    // Unwind
    String unwind;
    TACLabel unwindLabel = node.getSuccessor();
    if (unwindLabel == null) unwindLabel = node.getBlock().getUnwind();
    if (unwindLabel == null) unwind = "to caller";
    else unwind = "label " + symbol(unwindLabel);

    TACLabel label = new TACLabel(node.getMethod());

    String switchToken = nextTemp();
    writer.write(
        switchToken
            + " = catchswitch within none [label "
            + symbol(label)
            + "]"
            + " unwind "
            + unwind);
    writer.writeLeft(name(label) + ':');
    String padToken = nextTemp();
    node.setToken(padToken);
    writer.write(
        padToken
            + " = catchpad within "
            + switchToken
            + " [i8* bitcast (i32 (i8*, i8*)* "
            + exceptionMethod(node.getType())
            + " to i8*)]");

    exceptions.add(node.getType());

    writer.write(
        nextTemp()
            + " = load "
            + type(Type.EXCEPTION)
            + ", "
            + type(Type.EXCEPTION)
            + "* @__exceptionStorage");
    int offset = node.hasLocalStore() ? 0 : 1;
    writer.write(
        nextTemp(node)
            + " = bitcast "
            + type(Type.EXCEPTION)
            + " "
            + temp(offset)
            + " to "
            + type(node.getType()));
  }

  @Override
  public void visit(TACCatchRet node) throws ShadowException {
    TACCatchPad catchPad = node.getCatchPad();
    writer.write("catchret from " + catchPad.getToken() + " to label " + symbol(node.getLabel()));
  }

  @Override
  public void visit(TACLandingPad node) throws ShadowException {
    writer.write(nextTemp(node) + " = landingpad " + type(node));
    writer.indent(2);
    writer.write("cleanup");
    // Fix catches!!!
    List<TACCatch> catches = new ArrayList<>(node.getCatches().size());
    if (!node.getCatches().isEmpty()) {
      catches.addAll(node.getCatches());
      TACBlock block = node.getBlock();
      while (block != null) {
        TACLandingPad parentPad = block.getLandingPad();
        if (parentPad == null) block = null;
        else {
          for (TACCatch catch_ : parentPad.getCatches()) {
            boolean skip = false;
            // Starting at the end, make sure that this catch isn't already subsumed by a broader
            // exception
            for (int i = catches.size() - 1; i >= 0 && !skip; --i) {
              if (catch_.getType().isSubtype(catches.get(i).getType())) skip = true;
            }
            if (!skip) catches.add(catch_);
          }
          block = parentPad.getBlock().getParent();
        }
      }

      for (TACCatch catch_ : catches)
        writer.write("catch " + type(Type.CLASS) + ' ' + classOf(catch_.getType()));
    }

    writer.outdent(2);
  }

  @Override
  public void visit(TACCatch node) throws ShadowException {
    // writer.write(nextTemp() + " = extractvalue " + typeSymbol(node.getOperand(0)) + ", 0");
    writer.write(
        nextTemp()
            + " = call "
            + type(Type.EXCEPTION)
            + " @__shadow_catch(i8* "
            + symbol(node.getOperand(0))
            + ") nounwind");
    int offset = node.hasLocalStore() ? 0 : 1;
    writer.write(
        nextTemp(node)
            + " = bitcast "
            + type(Type.EXCEPTION)
            + ' '
            + temp(offset)
            + " to "
            + type(node.getType()));
  }

  @Override
  public void visit(TACResume node) throws ShadowException {
    writer.write("resume " + typeSymbol(node.getException()));
  }

  private static String interfaceData(Type type) {
    return "@_interfaceData" + type.toString(Type.MANGLE);
  }

  private static String exceptionComdat(ExceptionType type) {
    return "$_exceptionMethod" + type.toString(Type.MANGLE);
  }

  private static String exceptionMethod(ExceptionType type) {
    return "@_exceptionMethod" + type.toString(Type.MANGLE);
  }

  private static String interfaces(Type type) {
    return "@_interfaces" + type.toString(Type.MANGLE);
  }

  private static String genericInterfaces(Type type) {
    return "@_interfaces" + type.toString(Type.MANGLE | Type.TYPE_PARAMETERS);
  }

  public static String classOf(Type type) {
    if (type instanceof ArrayType arrayType)
      type = arrayType.convertToGeneric();

    if (type.isPrimitive()) return '@' + type.getTypeName() + ".class";
    else if (type.isFullyInstantiated()) return '@' + withGenerics(type, ".class");
    else return '@' + raw(type, ".class");
  }

  public static String methodTable(Type type) {
    if (type instanceof InterfaceType && type.isFullyInstantiated())
      return "@" + withGenerics(type, "._methods");
    else if (type instanceof ArrayType arrayType) {
      if (arrayType.isNullable()) return methodTable(Type.ARRAY_NULLABLE);
      else return methodTable(Type.ARRAY);
    }
    return "@" + raw(type, "._methods");
  }

  private static String methodTableType(Type type) {
    return methodTableType(type, true);
  }

  private static String methodTableType(Type type, boolean reference) {
    if (type instanceof ArrayType arrayType) {
      if (arrayType.isNullable()) return methodTableType(Type.ARRAY_NULLABLE, reference);
      else return methodTableType(Type.ARRAY, reference);
    }

    return "%" + raw(type, reference ? "._methods*" : "._methods");
  }

  private static String methodType(TACMethodName method) {
    return methodToString(method, true) + '*';
  }

  private static String methodType(MethodSignature method) {
    return methodToString(method, false, true) + '*';
  }

  private static String methodToString(TACMethod method) {
    return methodToString(method.getSignature(), true, true);
  }

  private static String methodToString(MethodSignature signature) {
    return methodToString(signature, true, true);
  }

  private static String methodToString(TACMethodPointer pointer) {
    StringBuilder sb = new StringBuilder();
    SequenceType returnTypes = pointer.getUninstantiatedReturnTypes();
    if (returnTypes.isEmpty()) sb.append("void");
    else if (returnTypes.size() == 1) sb.append(type(returnTypes.get(0)));
    else sb.append(type(returnTypes));

    sb.append(' ');
    return sb.toString();
  }

  private static String methodToString(
      MethodSignature signature, boolean name, boolean parameters) {
    StringBuilder sb = new StringBuilder();
    if (name && signature.isWrapper()) sb.append("private ");
    boolean primitiveOrInterfaceCreate =
        !signature.getOuter().isSimpleReference() && signature.isCreate();

    if (primitiveOrInterfaceCreate) {
      if (signature.getOuter()
          instanceof InterfaceType) // in situations where an interface contains a create
      sb.append(type(Type.OBJECT));
      else
        sb.append(
            type(
                signature.getOuter(),
                true)); // the nullable looks odd, but it gets the Object version of the primitive
    } else {
      SequenceType returnTypes = signature.getSignatureWithoutTypeArguments().getFullReturnTypes();
      if (returnTypes.isEmpty()) sb.append("void");
      else if (returnTypes.size() == 1) sb.append(type(returnTypes.get(0)));
      else sb.append(type(returnTypes));
    }
    sb.append(' ');
    if (name) sb.append(name(signature));
    if (parameters) {
      sb.append('(');
      boolean first = true;
      SequenceType parameterTypes =
          signature.getSignatureWithoutTypeArguments().getFullParameterTypes();

      for (ModifiedType parameterType : parameterTypes) {
        if (first) {
          first = false;
          sb.append(type(parameterType));
          if (name && signature.isCreate()) sb.append(" returned");
        } else sb.append(", ").append(type(parameterType));
      }
      sb.append(')');
    }
    return sb.toString();
  }

  private static String methodToString(TACMethodName method, boolean parameters) {
    return methodToString(method.getSignature(), false, parameters);
  }


  private static String sizeof(Type type, boolean simplify) {
    String name;
    if (type instanceof InterfaceType) {
      if (simplify)
        name = anonymousInterfaceType() + "*";
      else
        name = type(type) + "*";
    }
    else name = type(type, true);
    return "ptrtoint ("
            + name
            + " getelementptr ("
            + name.substring(0, name.length() - 1)
            + ", "
            + name
            + " null, i32 1) to i32)";
  }

  private static String sizeof(Type type) {
    return sizeof(type, false);
  }

  private static String type(ModifiedType type) {
    return type(type.getType(), type.getModifiers().isNullable());
  }

  protected static String type(Type type) {
    return type(type, false);
  }

  protected static String type(Type type, boolean nullable) {
    if (type == null) throw new NullPointerException();
    if (type instanceof ArrayType) return type((ArrayType) type);
    if (type instanceof SequenceType) return type((SequenceType) type);
    if (type instanceof MethodType) return type((MethodType) type);
    if (type instanceof AttributeType) return type((AttributeType) type);
    if (type instanceof ClassType) return type((ClassType) type, nullable);
    if (type instanceof InterfaceType) return type((InterfaceType) type);
    if (type instanceof TypeParameter) return type((TypeParameter) type);
    if (type instanceof MethodTableType) return type((MethodTableType) type);
    if (type instanceof PointerType) return type((PointerType) type);

    throw new IllegalArgumentException("Unknown type.");
  }

  protected static String type(ArrayType type) {
    if (type.isNullable()) return type(Type.ARRAY_NULLABLE);
    else return type(Type.ARRAY);
  }

  private static String type(SequenceType type) {
    if (type.isEmpty()) return "void";
    if (type.size() == 1) return type(type.get(0));
    StringBuilder sb = new StringBuilder("{ ");
    for (ModifiedType each : type) sb.append(type(each)).append(", ");
    return sb.replace(sb.length() - 2, sb.length(), " }").toString();
  }

  @SuppressWarnings("unused")
  private static String type(PointerType type) {
    return "i8*";
  }

  private static String type(AttributeType type) {
    return '%' + type.toString(Type.MANGLE) + '*';
  }

  private static String type(ClassType type, boolean nullable) {
    if (type.isPrimitive() && !nullable) return '%' + type.getTypeName();

    if (type == Type.NULL) return '%' + Type.OBJECT.toString(Type.MANGLE) + '*';

    return '%' + type.toString(Type.MANGLE) + '*';
  }

  private static String type(MethodType type) {
    StringBuilder sb = new StringBuilder();

    // Should never be a create
    SequenceType returnTypes = type.getTypeWithoutTypeArguments().getReturnTypes();

    if (returnTypes.isEmpty()) sb.append("void");
    else if (returnTypes.size() == 1) sb.append(type(returnTypes.get(0)));
    else sb.append(type(returnTypes));

    sb.append(" (");
    SequenceType parameterTypes = type.getTypeWithoutTypeArguments().getParameterTypes();
    Type outer = type.getOuter();
    if (outer == null || outer instanceof InterfaceType) sb.append(type(Type.OBJECT));
    else sb.append(type(outer));

    for (ModifiedType parameterType : parameterTypes) sb.append(", ").append(type(parameterType));

    sb.append(")*");

    return sb.toString();
  }

  private static String type(InterfaceType type) {
    return "{ " + methodTableType(type) + ", " + type(Type.OBJECT) + " }";
  }

  private static String anonymousInterfaceType() {
    return "{ " + type(Type.METHOD_TABLE) + ", " + type(Type.OBJECT) + " }";
  }

  private static String type(TypeParameter type) {
    return type(type.getClassBound());
  }

  private static String type(MethodTableType type) {
    return '%' + type.toString(Type.MANGLE) + '*';
  }

  private static String raw(Type type) {
    return raw(type, "");
  }

  private static String raw(Type type, String extra) {
    return type.toString(Type.MANGLE) + extra;
  }

  private static String withGenerics(Type type, String extra) {
    return type.toString(Type.MANGLE | Type.TYPE_PARAMETERS) + extra;
  }

  private String name(TACLabel label) {
    return label.toString();
  }

  private static String name(TACLocalStorage store) {
    return '%' + store.getVariable().getName() + '.' + store.getNumber();
  }

  private static String name(TACConstant constant) {
    return "@" + raw(constant.getFieldKey().parentType, "_C" + constant.getFieldKey().fieldName);
  }

  private static String name(TACMethodName method) {
    return name(method.getSignature());
  }

  private static String name(MethodSignature method) {
    return '@' + method.getMangledName();
  }

  public static String name(TACMethod method) {
    return name(method.getSignature());
  }

  public static String symbol(TACLabel node) {
    return '%' + node.toString();
  }

  private String symbol(TACOperand node) {
    // If garbage collected, referring to a phi or a store doesn't make sense
    // Instead, pull value from load of the same variable most recently before current node
    Object symbol;

    if (node instanceof TACUpdate && !(node instanceof TACLocalStore))
      symbol = ((TACUpdate) node).getValue().getData();
    else symbol = node.getData();

    if (symbol instanceof ShadowValue) symbol = literal((ShadowValue) symbol);

    if (symbol instanceof String) return (String) symbol;

    throw new NullPointerException();
  }

  private String typeSymbol(TACOperand node) {
    return typeSymbol(node, node);
  }

  private String typeSymbol(Type type, TACOperand node) {
    return typeText(type, symbol(node), false);
  }

  private String typeSymbol(ModifiedType type, TACOperand node) {
    return typeText(type, symbol(node));
  }

  private String literal(ShadowValue value) {
    if (value instanceof ShadowNull) {
      if (value.getType() instanceof InterfaceType) return "zeroinitializer";
      else return "null";
    }
    if (value instanceof ShadowUndefined) return "undef";
    if (value instanceof ShadowBoolean) return literal(((ShadowBoolean) value).getValue());
    if (value instanceof ShadowInteger) return literal(((ShadowInteger) value).getValue());
    if (value instanceof ShadowCode) return literal(((ShadowCode) value).getValue());
    if (value instanceof ShadowFloat) return literal(((ShadowFloat) value).getValue());
    if (value instanceof ShadowDouble) return literal(((ShadowDouble) value).getValue());
    if (value instanceof ShadowString) return literal(((ShadowString) value).getValue());

    throw new UnsupportedOperationException(value.getClass().toString());
  }

  private static String literal(boolean value) {
    return Boolean.toString(value);
  }

  private static String literal(long value) {
    return Long.toString(value);
  }

  private static String literal(BigInteger value) {
    return value.toString();
  }

  // TODO: make a separate one for float?
  private static String literal(double value) {
    long raw = Double.doubleToRawLongBits(value);
    StringBuilder sb = new StringBuilder("0x");
    for (int i = 0; i < 16; i++, raw <<= 4)
      sb.append(Character.forDigit((int) (raw >> 60) & 15, 16));
    return sb.toString();
  }

  private String literal(String value) {
    int index = stringLiterals.indexOf(value);
    if (index == -1) {
      index = stringLiterals.size();
      stringLiterals.add(value);
    }
    return "@_string" + index;
  }

  private String typeLiteral(boolean value) {
    return typeText(Type.BOOLEAN, literal(value));
  }

  private static String typeLiteral(int value) {
    return typeText(Type.INT, literal(value));
  }

  private static String typeLiteral(long value) {
    return typeText(Type.LONG, literal(value));
  }

  private String typeLiteral(String value) {
    return typeText(Type.STRING, literal(value));
  }

  private String typeLiteral(ShadowValue value) {
    return typeText(value, literal(value));
  }

  @SuppressWarnings("SameParameterValue")
  private String typeTemp(Type type, int offset) {
    return typeText(type, temp(offset));
  }

  private static String typeText(Type type, String name) {
    return combine(type(type), name, false);
  }

  private static String typeText(Type type, String name, boolean reference) {
    return combine(type(type), name, reference);
  }

  private static String typeText(ModifiedType type, String name) {
    return combine(type(type), name, false);
  }

  @SuppressWarnings("SameParameterValue")
  private static String typeText(ModifiedType type, String name, boolean reference) {
    return combine(type(type), name, reference);
  }

  private static String combine(String type, String name, boolean reference) {
    StringBuilder sb = new StringBuilder(type);
    if (reference) sb.append('*');
    return sb.append(' ').append(name).toString();
  }

  private void declareGenericClass(Type generic) throws ShadowException {
    writer.write(classOf(generic) + " = external global " + type(Type.GENERIC_CLASS));
  }

  private void writeGenericClass(Type generic) throws ShadowException {

    // This comdat stuff is supposed to allow generic classes to be defined in multiple files and
    // merged at link time
    //writer.write("$" + withGenerics(generic, ".class") + " = comdat any");

    Type genericAsObject;

    if (generic instanceof ArrayType) genericAsObject = ((ArrayType) generic).convertToGeneric();
    else genericAsObject = generic;
    Type noArguments = genericAsObject.getTypeWithoutTypeArguments();
    List<ModifiedType> parameterList = genericAsObject.getTypeParameters();

    String interfaceData;
    String interfaces;
    int flags = GENERIC;

    if (genericAsObject instanceof InterfaceType) {
      flags |= INTERFACE;
      interfaceData = interfaces = " zeroinitializer, ";
    } else {
      ArrayList<InterfaceType> interfaceList = genericAsObject.getAllInterfaces();
      interfaceData =
          " bitcast ({ %ulong, "
              + type(Type.GENERIC_CLASS)
              + ", "
              + //methodTableType(Type.ARRAY)
                  type(Type.METHOD_TABLE)
              + ", %long, ["
              + interfaceList.size()
              + " x "
              + type(Type.METHOD_TABLE)
              + "]}* "
              + interfaceData(noArguments)
              + " to "
              + type(Type.ARRAY)
              + "), ";
      interfaces =
          " bitcast ({ %ulong, "
              + type(Type.GENERIC_CLASS)
              + ", "
              + //methodTableType(Type.ARRAY)
                  type(Type.METHOD_TABLE)
              + ", %long, ["
              + interfaceList.size()
              + " x "
              + type(Type.CLASS)
              + "]}* "
              + genericInterfaces(genericAsObject)
              + " to "
              + type(Type.ARRAY)
              + "), ";
    }

    // get parent class
    String parentClass;
    if (genericAsObject instanceof ClassType) {
      ClassType parent = ((ClassType) genericAsObject).getExtendType();
      if (parent.isFullyInstantiated() || parent instanceof ArrayType)
        parentClass =
            type(Type.CLASS)
                + " ("
                + typeText(Type.GENERIC_CLASS, classOf(parent))
                + " to "
                + type(Type.CLASS)
                + ")";
      else parentClass = typeText(Type.CLASS, classOf(parent));
    } else parentClass = type(Type.CLASS) + " null";

    int classListSize = parameterList.size();
    if (generic instanceof ClassType) {
      ClassType classType = (ClassType) genericAsObject;
      if (classType.hasDependencyList()) classListSize += classType.getDependencyList().size();
    }

    String name;


    // As an optimization for arrays, store no name, since the full name can be retrieved from base class
    if (noArguments == Type.ARRAY || noArguments == Type.ARRAY_NULLABLE) {
      name = noArguments == Type.ARRAY_NULLABLE ? "nullable " : "";
      flags |= ARRAY;
    } else name = genericAsObject.toString(Type.PACKAGES);

    writer.write(
        classOf(generic)
            + " = unnamed_addr constant %"
            //    + " = linkonce  constant  %"
            + raw(Type.GENERIC_CLASS)
            + " { "
            + type(Type.ULONG)
            + " "
            + literal(-1)
            + ", "
            + // reference count
            typeText(Type.CLASS, classOf(Type.GENERIC_CLASS))
            + ", "
            + // class
            //methodTableType(Type.GENERIC_CLASS)
                type(Type.METHOD_TABLE)
            + " "
            + methodTable(Type.GENERIC_CLASS)
            + ", "
            + // methods
            typeText(Type.ARRAY, interfaceData)
            + // data (method tables)
            typeText(Type.ARRAY, interfaces)
            + // interfaces
            typeLiteral(name)
            + ", "
            + // name
            parentClass
            + ", "
            + // parent
            typeLiteral(flags)
            + ", "
            + // flags
            typeText(Type.INT, sizeof(noArguments, true))
            + ", "
            + // size
            type(Type.ARRAY)
            + " bitcast ( { %ulong, "
            + type(Type.GENERIC_CLASS)
            + ", "
            + //methodTableType(Type.ARRAY)
                type(Type.METHOD_TABLE)
            + ", %long, ["
            + classListSize
            + " x "
            + type(Type.CLASS)
            + "]}* "
            + "@_parameters"
            + generic.toString(Type.MANGLE | Type.TYPE_PARAMETERS)
            + " to "
            + type(Type.ARRAY)
            + "), "
            + // parameters
            type(Type.ARRAY)
            + " bitcast ( { %ulong, "
            + type(Type.GENERIC_CLASS)
            + ", "
            + //methodTableType(Type.ARRAY)
                type(Type.METHOD_TABLE)
            + ", %long, ["
            + parameterList.size()
            + " x "
            + type(Type.METHOD_TABLE)
            + "]}* "
            + "@_tables"
            + generic.toString(Type.MANGLE | Type.TYPE_PARAMETERS)
            + " to "
            + type(Type.ARRAY)
                + ")}"); // tables
            //+ ")}, comdat"); // tables
  }

  private void writeGenericClassSupportingMaterial(Type generic) throws ShadowException {

    boolean first;
    Type genericAsObject;
    if (generic instanceof ArrayType) genericAsObject = ((ArrayType) generic).convertToGeneric();
    else genericAsObject = generic;

    if (generic instanceof ClassType) {
      List<InterfaceType> interfaces = genericAsObject.getAllInterfaces();
      StringBuilder sb =
          new StringBuilder(
              "{ %ulong -1, "
                  + typeText(Type.GENERIC_CLASS, classOf(new ArrayType(Type.CLASS)))
                  + ", "
                  + //methodTableType(Type.ARRAY)
                      type(Type.METHOD_TABLE)
                  + " "
                  + methodTable(Type.ARRAY)
                  + ", "
                  + typeLiteral((long) interfaces.size())
                  + ", ["
                  + interfaces.size()
                  + " x "
                  + type(Type.CLASS)
                  + "] [");
      first = true;

      for (InterfaceType _interface : interfaces) {
        if (first) first = false;
        else sb.append(", ");
        if (_interface.isFullyInstantiated())
          sb.append(type(Type.CLASS))
              .append(" bitcast (")
              .append(
                  typeText(
                      Type.GENERIC_CLASS, classOf(_interface) + " to " + type(Type.CLASS) + ")"));
        else sb.append(typeText(Type.CLASS, classOf(_interface)));
      }

      //sb.append("]}, comdat");
      sb.append("]}");


      //writer.write(
          //"$_interfaces" + generic.toString(Type.MANGLE | Type.TYPE_PARAMETERS) + " = comdat any");
      writer.write(
          genericInterfaces(generic)
              //+ " = linkonce_odr unnamed_addr constant {%ulong, "
                  + " = linkonce constant {%ulong, "
              + type(Type.GENERIC_CLASS)
              + ", "
              + //methodTableType(Type.ARRAY)
                  type(Type.METHOD_TABLE)
              + ", "
              + type(Type.LONG)
              + ", ["
              + interfaces.size()
              + " x "
              + type(Type.CLASS)
              + "]} "
              + sb);
    }

    int classListSize =
        genericAsObject.getTypeParameters() != null
            ? genericAsObject.getTypeParameters().size()
            : 0;
    if (genericAsObject instanceof ClassType genericClass) {
      if (genericClass.hasDependencyList())
        classListSize += genericClass.getDependencyList().size();
    }

    // write definitions of type parameters
    List<ModifiedType> parameterList = genericAsObject.getTypeParameters();
    StringBuilder parameters =
        new StringBuilder(
            "{ %ulong -1, "
                + typeText(Type.GENERIC_CLASS, classOf(new ArrayType(Type.CLASS)))
                + ", "
                + //methodTableType(Type.ARRAY)
                    type(Type.METHOD_TABLE)
                + " "
                + methodTable(Type.ARRAY)
                + ", "
                + typeLiteral((long) classListSize)
                + ", ["
                + classListSize
                + " x "
                + type(Type.CLASS)
                + "] [");
    StringBuilder tables =
        new StringBuilder(
            "{ %ulong -1, "
                + typeText(Type.GENERIC_CLASS, classOf(new ArrayType(Type.METHOD_TABLE)))
                + ", "
                + //methodTableType(Type.ARRAY)
                    type(Type.METHOD_TABLE)
                + " "
                + methodTable(Type.ARRAY)
                + ", "
                + typeLiteral((long) parameterList.size())
                + ", ["
                + parameterList.size()
                + " x "
                + type(Type.METHOD_TABLE)
                + "] [");
    first = true;

    for (ModifiedType parameter : parameterList) {
      Type parameterType = parameter.getType();
      Type parameterWithoutArguments = parameterType.getTypeWithoutTypeArguments();

      if (first) first = false;
      else {
        parameters.append(", ");
        tables.append(", ");
      }

      // handle classes
      parameters.append(type(Type.CLASS)).append(" ");
      if (parameterType.isFullyInstantiated() || parameterType instanceof ArrayType)
        parameters
            .append("bitcast (")
            .append(type(Type.GENERIC_CLASS))
            .append(" ")
            .append(classOf(parameterType))
            .append(" to ")
            .append(type(Type.CLASS))
            .append(")");
      else parameters.append(classOf(parameterType));

      // handle corresponding method tables
      if (parameterType instanceof InterfaceType)
        tables.append(type(Type.METHOD_TABLE)).append(" null"); // no method table for interfaces
      else
        tables
            .append(type(Type.METHOD_TABLE))
            .append(" ")
            .append(methodTable(parameterWithoutArguments));
    }

    // handle extra class dependencies

    if (genericAsObject instanceof ClassType classType) {
      if (classType.hasDependencyList())
        for (ModifiedType parameter : classType.getDependencyList()) {
          Type parameterType = parameter.getType();

          // arrays are in their "generic" form and should be turned back
          //TODO: Is this true? Shouldn't we keep them generic?
          /*
          if (parameterType.getTypeWithoutTypeArguments().equals(Type.ARRAY))
            parameterType = new ArrayType(parameterType.getTypeParameters().getType(0));
          else if (parameterType.getTypeWithoutTypeArguments().equals(Type.ARRAY_NULLABLE))
            parameterType = new ArrayType(parameterType.getTypeParameters().getType(0), true);
           */

          parameters.append(", ");
          parameters.append(type(Type.CLASS)).append(" ");
          if (parameterType.isFullyInstantiated() || parameterType instanceof ArrayType)
            parameters
                .append("bitcast (")
                .append(type(Type.GENERIC_CLASS))
                .append(" ")
                .append(classOf(parameterType))
                .append(" to ")
                .append(type(Type.CLASS))
                .append(")");
          else parameters.append(classOf(parameterType));
        }
    }

    //parameters.append("]}, comdat");
    parameters.append("]}");
    //tables.append("]}, comdat");
    tables.append("]}");

    // This comdat stuff is supposed to allow generic classes to be defined in multiple files and
    // merged at link time
    String mangledGeneric = generic.toString(Type.MANGLE | Type.TYPE_PARAMETERS);
    //writer.write("$_parameters" + mangledGeneric + " = comdat any");
    writer.write(
        "@_parameters"
            + mangledGeneric
            //+ " = linkonce_odr unnamed_addr constant { %ulong, "
                + " = linkonce  constant { %ulong, "
            + type(Type.GENERIC_CLASS)
            + ", "
            + //methodTableType(Type.ARRAY)
                type(Type.METHOD_TABLE)
            + ", %long, ["
            + classListSize
            + " x "
            + type(Type.CLASS)
            + "] } "
            + parameters);

    //writer.write("$_tables" + mangledGeneric + " = comdat any");
    writer.write(
        "@_tables"
            + mangledGeneric
            //+ " = linkonce_odr unnamed_addr constant { %ulong, "
                + " = linkonce  constant { %ulong, "
            + type(Type.GENERIC_CLASS)
            + ", "
            + //methodTableType(Type.ARRAY)
                type(Type.METHOD_TABLE)
            + ", %long, ["
            + parameterList.size()
            + " x "
            + type(Type.METHOD_TABLE)
            + "] } "
            + tables);
  }

  public void close() throws IOException {
    writer.close();
  }
}
