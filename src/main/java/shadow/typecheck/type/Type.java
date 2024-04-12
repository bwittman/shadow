package shadow.typecheck.type;

import shadow.ShadowException;
import shadow.doctool.Documentation;
import shadow.parse.Context;
import shadow.parse.Context.AssignmentKind;
import shadow.parse.ShadowParser;
import shadow.parse.ShadowParser.NameContext;
import shadow.typecheck.BaseChecker;
import shadow.typecheck.BaseChecker.SubstitutionKind;
import shadow.typecheck.ErrorReporter;
import shadow.typecheck.Package;
import shadow.typecheck.TypeCheckException.Error;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** A representation of a Shadow type. */
@SuppressWarnings("StaticInitializerReferencesSubClass")
public abstract class Type implements Comparable<Type> {
  private final String typeName;
  /** A string that represents the type */
  private Modifiers modifiers;

  private Type outer; // Outer class or interface
  private Map<String, Type> innerTypes = new HashMap<>();

  private Package _package;
  private SequenceType typeParameters = null;
  private boolean parameterized = false;
  protected Type typeWithoutTypeArguments = this;
  private Documentation documentation;

  private ArrayList<InterfaceType> interfaces = new ArrayList<>();

  // a linked hash maps iterates over the elements in the order they were added
  // this feature is needed to for walking the fields in order in constructors
  private final LinkedHashMap<String, ShadowParser.VariableDeclaratorContext> fieldTable =
      new LinkedHashMap<>();
  private final Map<String, ShadowParser.VariableDeclaratorContext> constantTable = new HashMap<>();

  // A map from methodName -> list of all overloads for that method
  private final HashMap<String, List<MethodSignature>> methodTable = new HashMap<>();
  private final Set<Type> usedTypes = new HashSet<>();
  private final Set<Type> mentionedTypes = new HashSet<>();
  private final Set<Type> partiallyInstantiatedClasses = new TreeSet<>();
  private final List<Type> typeParameterDependencies = new ArrayList<>();
  private final TypeArgumentCache instantiatedTypes = new TypeArgumentCache();
  private final Map<String, ImportInformation> importedItems = new HashMap<>();

  private String hashName = null;

  /*
   * Predefined system types needed for Shadow
   */

  public static ClassType OBJECT = null;
  public static ClassType CLASS = null; // metaclass for holding normal :class variables
  public static ClassType GENERIC_CLASS = null; // metaclass for holding generic :class variables
  public static ClassType ARRAY = null; // object representation of all array types
  public static ClassType ARRAY_NULLABLE = null; // object representation of nullable array types

  public static ClassType METHOD = null; // used to hold method references
  public static ClassType METHOD_TABLE =
      null; // really just a pointer for method tables, but we sometimes act like it is a class

  public static ClassType ENUM = null; // weirdly, the base class for enum is not an EnumType
  public static ClassType ATTRIBUTE = null; // similarly for attributes
  public static ExceptionType EXCEPTION = null;
  public static ExceptionType CAST_EXCEPTION = null;
  public static ExceptionType INDEX_OUT_OF_BOUNDS_EXCEPTION = null;
  public static ExceptionType ASSERT_EXCEPTION = null;
  public static ExceptionType UNEXPECTED_NULL_EXCEPTION = null;
  public static ExceptionType INTERFACE_CREATE_EXCEPTION = null;

  public static ClassType BOOLEAN = null;
  public static ClassType BYTE = null;
  public static ClassType CODE = null;
  public static ClassType DOUBLE = null;
  public static ClassType FLOAT = null;
  public static ClassType INT = null;
  public static ClassType LONG = null;
  public static ClassType SHORT = null;

  public static ClassType UBYTE = null;
  public static ClassType UINT = null;
  public static ClassType ULONG = null;
  public static ClassType USHORT = null;

  public static ClassType STRING = null;
  public static ClassType MUTABLE_STRING = null;
  public static ClassType ADDRESS_MAP = null; // used for copying
  public static ClassType POINTER = null;
  public static ClassType THREAD = null;
  public static SingletonType THREAD_CURRENT = null;

  public static final ClassType UNKNOWN =
      new ClassType(
          "Unknown Type",
          new Modifiers(),
          null,
          null); // UNKNOWN type used for placeholder when typechecking goes wrong
  public static final ClassType NULL =
      new ClassType("null", new Modifiers(Modifiers.IMMUTABLE), null, null);
  public static final VarType VAR =
      new VarType(); // VAR type used for placeholder for variables declared with var, until type is
  // known

  /*
   * Predefined interfaces needed for Shadow
   */

  public static InterfaceType CAN_COMPARE = null;
  public static InterfaceType CAN_EQUAL = null;
  public static InterfaceType CAN_INDEX = null;
  public static InterfaceType CAN_INDEX_NULLABLE = null;
  public static InterfaceType CAN_INDEX_STORE = null;
  public static InterfaceType CAN_INDEX_STORE_NULLABLE = null;
  public static InterfaceType CAN_ITERATE = null;
  public static InterfaceType CAN_ITERATE_NULLABLE = null;
  public static InterfaceType ITERATOR = null;
  public static InterfaceType ITERATOR_NULLABLE = null;
  public static InterfaceType NUMBER = null;
  public static InterfaceType INTEGER = null;
  public static InterfaceType CAN_ADD = null;
  public static InterfaceType CAN_SUBTRACT = null;
  public static InterfaceType CAN_MULTIPLY = null;
  public static InterfaceType CAN_DIVIDE = null;
  public static InterfaceType CAN_MODULUS = null;
  public static InterfaceType CAN_NEGATE = null;
  public static InterfaceType CAN_RUN = null;

  // constants used for options in toString()
  private static int bits = 0;
  public static final int NO_OPTIONS = 0;
  public static final int PACKAGES = 1 << bits++;
  public static final int TYPE_PARAMETERS = 1 << bits++;
  public static final int PARAMETER_BOUNDS = 1 << bits++;
  public static final int MANGLE = 1 << bits++;
  public static final int MANGLE_IMPORT_METHOD = 1 << bits++;
  public static final int NO_NULLABLE = 1 << bits++;

    /*
     * The following types must be added because they can appear in
     * generated code without appearing inside the Shadow source at all.
     *
     * DANGER: Do not call this method before type collection has been done;
     * otherwise, some of these values will be null.
     */
  public static List<Type> getCoreTypes() {
      if (coreTypes == null) {
          coreTypes = Collections.unmodifiableList(Arrays.asList(
          Type.OBJECT,
          // Address map for deep copies
          Type.ADDRESS_MAP,
          // Class management
          Type.CLASS,
          Type.GENERIC_CLASS,
          // Array wrapper classes
          Type.ARRAY,
          Type.ARRAY_NULLABLE,
          // Used for method references
          Type.METHOD,
          // Iterators for foreach loops
          Type.ITERATOR,
          Type.ITERATOR_NULLABLE,
          // Exceptions
          Type.EXCEPTION,
          Type.CAST_EXCEPTION,
          Type.INDEX_OUT_OF_BOUNDS_EXCEPTION,
          Type.INTERFACE_CREATE_EXCEPTION,
          Type.UNEXPECTED_NULL_EXCEPTION,
          // String
          Type.STRING,
          Type.MUTABLE_STRING,
          // ubyte array
          new ArrayType(Type.UBYTE),
          // method table array
          new ArrayType(Type.METHOD_TABLE),
          new ArrayType(Type.CLASS),
          Type.THREAD,
          // Add all primitive types (since their Object versions might be used in casts)
          Type.BOOLEAN,
          Type.BYTE,
          Type.CODE,
          Type.DOUBLE,
          Type.FLOAT,
          Type.INT,
          Type.LONG,
          Type.SHORT,
          Type.UBYTE,
          Type.UINT,
          Type.ULONG,
          Type.USHORT
        ));
      }
      return coreTypes;
  }

  /*
   * The following types must be added because they can appear in
   * generated code without appearing inside the Shadow source at all.
   *
   * DANGER: Do not call this method before type collection has been done;
   * otherwise, some of these values will be null.
   */
  public static List<Type> getGenericSupportingTypes() {
    if (genericSupportingTypes == null) {
      genericSupportingTypes = Collections.unmodifiableList(Arrays.asList(
              Type.OBJECT,
              // Address map for deep copies
              Type.ADDRESS_MAP,
              // Class management
              Type.CLASS,
              Type.GENERIC_CLASS,
              // Array wrapper classes
              Type.ARRAY,
              Type.ARRAY_NULLABLE,
              // String
              Type.STRING,
              // Add all primitive types (since their Object versions might be used in casts)
              Type.BOOLEAN,
              Type.BYTE,
              Type.CODE,
              Type.DOUBLE,
              Type.FLOAT,
              Type.INT,
              Type.LONG,
              Type.SHORT,
              Type.UBYTE,
              Type.UINT,
              Type.ULONG,
              Type.USHORT
      ));
    }
    return genericSupportingTypes;
  }

  private static List<Type> coreTypes = null;
  private static List<Type> genericSupportingTypes = null;

  private static class TypeArgumentCache {
    public ModifiedType argument;
    public Type instantiatedType;
    public List<TypeArgumentCache> children;

    public String toString() {
      if (instantiatedType != null) return instantiatedType.toString();
      else {
        StringBuilder result =
            new StringBuilder(argument == null ? "[ROOT]" : argument.getType().toString());
        if (children != null) {
          result.append(" children: ");
          for (TypeArgumentCache cache : children)
            result.append(cache.argument.getType().toString()).append(" ");
        }

        return result.toString();
      }
    }
  }

  public static class ImportInformation {
    private final Path importPath;
    private final NameContext
        importName; // Doesn't include package information, since it's needed only for inner classes
    private Type type;

    public ImportInformation(Path importPath, NameContext importName) {
      this.importPath = importPath;
      this.importName = importName;
    }

    public Path getImportPath() {
      return importPath;
    }

    public NameContext getImportName() {
      return importName;
    }

    public void setType(Type type) {
      this.type = type;
    }

    public Type getType() {
      return type;
    }
  }

  private static List<ModifiedType> getArguments(
      Type type, List<ModifiedType> values, List<ModifiedType> replacements)
      throws InstantiationException {
    List<ModifiedType> arguments = new ArrayList<>();
    SequenceType typeParameters = type.getTypeParameters();

    for (ModifiedType parameter : typeParameters) {
      Type parameterType = parameter.getType();
      if (parameterType instanceof TypeParameter) {
        boolean found = false;
        for (int i = 0; i < values.size() && !found; ++i) {
          if (values.get(i).getType().equals(parameterType)) {
            arguments.add(replacements.get(i));
            found = true;
          }
        }
      } else if (parameterType.isParameterized())
        arguments.add(
            new SimpleModifiedType(
                parameterType.replace(parameterType.getTypeParameters(), replacements),
                parameter.getModifiers()));
      else arguments.add(parameter);
    }

    return arguments;
  }

  public Type getTypeWithoutTypeArguments() {
    return typeWithoutTypeArguments;
  }

  public Type getInstantiation(
      Type type, List<ModifiedType> values, List<ModifiedType> replacements)
      throws InstantiationException {
    if (type.isParameterized()) {
      List<ModifiedType> arguments = getArguments(type, values, replacements);
      TypeArgumentCache types = instantiatedTypes;

      for (ModifiedType argument : arguments) {
        if (types.children == null) return null;

        boolean found = false;
        for (int i = 0; !found && i < types.children.size(); ++i) {
          TypeArgumentCache child = types.children.get(i);

          if (child.argument != null
              && child.argument.getType().equals(argument.getType())
              && child.argument.getModifiers().equals(argument.getModifiers())) {
            types = child;
            found = true;
          }
        }

        if (!found) return null;
      }

      return types.instantiatedType;
    }

    return type;
  }

  public void addInstantiation(
      Type type, List<ModifiedType> values, List<ModifiedType> replacements, Type newType)
      throws InstantiationException {
    List<ModifiedType> arguments = getArguments(type, values, replacements);
    TypeArgumentCache types = instantiatedTypes;

    for (ModifiedType argument : arguments) {
      if (types.children == null) types.children = new ArrayList<>();

      boolean found = false;
      for (int i = 0; !found && i < types.children.size(); ++i) {
        TypeArgumentCache child = types.children.get(i);

        if (child.argument != null
            && child.argument.getType().equals(argument.getType())
            && child.argument.getModifiers().equals(argument.getModifiers())) {
          types = child;
          found = true;
        }
      }

      if (!found) {
        TypeArgumentCache newChild = new TypeArgumentCache();
        newChild.argument = argument;
        types.children.add(newChild);
        types = newChild;
      }
    }

    types.instantiatedType = newType;
  }

  // used to clear out types between runs of the JUnit tests
  // otherwise, types can become mixed between two different runs of the type checker
  public static void clearTypes() {
    OBJECT = null;
    METHOD = null;
    METHOD_TABLE = null;
    CAST_EXCEPTION = null;
    INDEX_OUT_OF_BOUNDS_EXCEPTION = null;
    UNEXPECTED_NULL_EXCEPTION = null;
    INTERFACE_CREATE_EXCEPTION = null;
    ASSERT_EXCEPTION = null;
    CLASS = null;
    ARRAY = null;
    ARRAY_NULLABLE = null;
    ENUM = null;
    ATTRIBUTE = null;
    EXCEPTION = null;
    GENERIC_CLASS = null;
    BOOLEAN = null;
    BYTE = null;
    CODE = null;
    DOUBLE = null;
    FLOAT = null;
    INT = null;
    LONG = null;
    SHORT = null;
    UBYTE = null;
    UINT = null;
    ULONG = null;
    USHORT = null;
    STRING = null;
    MUTABLE_STRING = null;
    ADDRESS_MAP = null;
    CAN_COMPARE = null;
    CAN_EQUAL = null;
    CAN_INDEX = null;
    CAN_INDEX_NULLABLE = null;
    CAN_INDEX_STORE = null;
    CAN_INDEX_STORE_NULLABLE = null;
    CAN_ITERATE = null;
    CAN_ITERATE_NULLABLE = null;
    ITERATOR = null;
    ITERATOR_NULLABLE = null;
    NUMBER = null;
    INTEGER = null;
    CAN_ADD = null;
    CAN_SUBTRACT = null;
    CAN_MULTIPLY = null;
    CAN_DIVIDE = null;
    CAN_MODULUS = null;
    CAN_NEGATE = null;

    POINTER = null;
    CAN_RUN = null;
    THREAD = null;

    THREAD_CURRENT = null;

    exceptionType = null;

    AttributeType.clearTypes();
  }

  /*
   * Constructors
   */

  public Type(String typeName) {
    this(typeName, new Modifiers());
  }

  public Type(String typeName, Modifiers modifiers) {
    this(typeName, modifiers, null);
  }

  public Type(String typeName, Modifiers modifiers, Documentation documentation) {
    this(typeName, modifiers, documentation, null);
  }

  public Type(String typeName, Modifiers modifiers, Documentation documentation, Type outer) {
    this(typeName, modifiers, documentation, outer, (outer == null ? null : outer._package));
  }

  public Type(
      String typeName,
      Modifiers modifiers,
      Documentation documentation,
      Type outer,
      Package _package) {
    this.typeName = typeName;
    this.modifiers = modifiers;
    this.documentation = documentation;
    this.outer = outer;
    this._package = _package;
  }

  public String getTypeName() {
    return typeName;
  }

  public final String getHashName() {
    if (hashName == null) hashName = toString(Type.PACKAGES | Type.TYPE_PARAMETERS | Type.MANGLE);

    return hashName;
  }

  protected final void invalidateHashName() {
    hashName = null;
  }

  public final String toString() {
    return toString(PACKAGES | TYPE_PARAMETERS); // no bounds
  }

  public String toString(int options) {
    StringBuilder builder = new StringBuilder();

    if (getOuter() == null) {
      String packageName;
      // mangled primitives still get package (for wrapper)
      if ((options & MANGLE) != 0) {
        if (_package == null || _package.getQualifiedName().isEmpty()) packageName = "default";
        else packageName = _package.getMangledName() + "..";

        builder.append(packageName);
        builder.append(mangle(typeName));
      } else if (!isPrimitive() && (options & PACKAGES) != 0) {
        if (_package == null || _package.getQualifiedName().isEmpty()) packageName = "default@";
        else packageName = _package.getQualifiedName() + "@";

        builder.append(packageName);
        builder.append(typeName);
      } else // unmangled primitives
      builder.append(typeName);
    } else if ((options & MANGLE) != 0)
      builder
          .append(getOuter().toString(options & ~TYPE_PARAMETERS))
          .append('.')
          .append(mangle(typeName));
    else
      builder.append(getOuter().toString(options & ~TYPE_PARAMETERS)).append(':').append(typeName);

    if (isParameterized() && (options & TYPE_PARAMETERS) != 0) {
      if ((options & MANGLE) != 0)
        builder.append(getTypeParameters().toString("_L", "_R", options));
      else builder.append(getTypeParameters().toString("<", ">", options));
    }

    return builder.toString();
  }

  public Modifiers getModifiers() {
    return modifiers;
  }

  public void setModifiers(Modifiers modifiers) {
    this.modifiers = modifiers;
  }

  public void addModifier(int modifier) {
    modifiers.addModifier(modifier);
  }

  @Override
  public boolean equals(Object object) {
    if (object instanceof Type type) {
      return equals(type);
    } else return false;
  }

  // separate from equals() because we need certain different types to be equivalent in hash tables
  public boolean equals(Type type) {
    if (type != null) {
      if (type == this) return true;

      if (getPackage() == type.getPackage() && type.getTypeName().equals(getTypeName())) {
        return !isParameterized() || type.typeParameters.matches(typeParameters);
      } else return false;
    } else return false;
  }

  protected boolean isNumericalSubtype(Type t) {
    if (this.equals(BYTE)) {
      return t.equals(SHORT)
          || t.equals(INT)
          || t.equals(LONG)
          || t.equals(FLOAT)
          || t.equals(DOUBLE);
    } else if (this.equals(CODE)) // just like uint?
    {
      return t.equals(UINT)
          || t.equals(ULONG)
          || t.equals(LONG)
          || t.equals(FLOAT)
          || t.equals(DOUBLE);
    } else if (this.equals(SHORT)) {
      return t.equals(INT) || t.equals(LONG) || t.equals(FLOAT) || t.equals(DOUBLE);
    } else if (this.equals(INT)) {
      return t.equals(LONG) || t.equals(FLOAT) || t.equals(DOUBLE);
    } else if (this.equals(LONG)) {
      return t.equals(FLOAT) || t.equals(DOUBLE);
    } else if (this.equals(FLOAT)) {
      return t.equals(DOUBLE);
    } else if (this.equals(UBYTE)) {
      return t.equals(CODE)
          || t.equals(USHORT)
          || t.equals(UINT)
          || t.equals(ULONG)
          || t.equals(SHORT)
          || t.equals(INT)
          || t.equals(LONG)
          || t.equals(FLOAT)
          || t.equals(DOUBLE);
    } else if (this.equals(UINT)) {
      return t.equals(CODE)
          || t.equals(ULONG)
          || t.equals(LONG)
          || t.equals(FLOAT)
          || t.equals(DOUBLE);
      // return t.equals(ULONG) || t.equals(LONG) || t.equals(FLOAT) || t.equals(DOUBLE);
    } else if (this.equals(ULONG)) {
      return t.equals(FLOAT) || t.equals(DOUBLE);
    } else if (this.equals(USHORT)) {
      return t.equals(CODE)
          || t.equals(UINT)
          || t.equals(ULONG)
          || t.equals(INT)
          || t.equals(LONG)
          || t.equals(FLOAT)
          || t.equals(DOUBLE);
    } else return false;
  }

  public static int getWidth(ModifiedType type) {
    if (type.getModifiers().isNullable())
      return OBJECT.getWidth(); // nullable makes a primitive type a reference
    return type.getType().getWidth();
  }

  public int getWidth() {
    if (this == NULL) return OBJECT.getWidth();
    if (this.equals(BYTE) || this.equals(UBYTE) || this.equals(BOOLEAN)) return 1;
    else if (this.equals(SHORT) || this.equals(USHORT)) return 2;
    else if (this.equals(INT) || this.equals(UINT) || this.equals(CODE) || this.equals(FLOAT))
      return 4;
    else if (this.equals(LONG) || this.equals(ULONG) || this.equals(DOUBLE)) return 8;

    return 6; // for objects?  So that they're always considered between 4 and 8 bytes and not equal
    // to any primitive?
  }

  public boolean isSimpleReference() {
    return getWidth() == OBJECT.getWidth();
  }

  @Override
  public final int hashCode() {
    return getHashName().hashCode();
  }

  public boolean isString() {
    return this.equals(Type.STRING);
  }

  public boolean hasOuter() {
    return outer != null;
  }

  public Type getOuter() {
    return outer;
  }

  public void setOuter(Type outer) {
    this.outer = outer;
  }

  protected void setInnerTypes(Map<String, Type> innerTypes) {
    this.innerTypes = innerTypes;
  }

  public Map<String, Type> getInnerTypes() {
    return innerTypes;
  }

  public void addInnerType(String name, Type innerClass) {
    innerTypes.put(name, innerClass);
    innerClass.setOuter(this);
  }

  public boolean containsInnerType(String className) {
    return innerTypes.containsKey(className);
  }

  public boolean recursivelyContainsInnerType(Type type) {
    if (innerTypes.containsValue(type)) return true;

    for (Type innerClass : innerTypes.values())
      if (innerClass.recursivelyContainsInnerType(type)) return true;

    return false;
  }

  public final boolean recursivelyContainsConstant(String fieldName) {
    return recursivelyGetConstant(fieldName) != null;
  }

  public final boolean recursivelyContainsMethod(String symbol) {
    return recursivelyGetMethodOverloads(symbol).size() > 0;
  }

  public Type getInnerType(String className) {
    if (className.contains(":")) {
      int colon = className.indexOf(':');
      String prefix = className.substring(0, colon);
      Type inner = innerTypes.get(prefix);
      if (inner != null) return inner.getInnerType(className.substring(colon + 1));
      else return null;
    }

    return innerTypes.get(className);
  }

  public Set<Type> recursivelyGetInnerTypes() {
    Set<Type> innerTypes = new HashSet<>();
    recursivelyGetInnerTypes(innerTypes);
    return innerTypes;
  }

  /**
   * Recursively gets all inner types contained within this type (and within its inner types, etc.)
   */
  protected void recursivelyGetInnerTypes(Set<Type> allInnerTypes) {
    Collection<Type> currentInnerTypes = getInnerTypes().values();
    allInnerTypes.addAll(currentInnerTypes);
    for (Type type : currentInnerTypes) {
      type.recursivelyGetInnerTypes(allInnerTypes);
    }
  }

  // For math
  public final boolean isNumerical() {
    return isPrimitive() && !this.equals(BOOLEAN); // Includes CODE
  }

  // For cases where integers are required (bitwise operations, array bounds,
  // switch statements, etc.)
  public final boolean isIntegral() {
    return this.equals(BYTE)
        || this.equals(CODE)
        || this.equals(SHORT)
        || this.equals(INT)
        || this.equals(LONG)
        || this.equals(UBYTE)
        || this.equals(UINT)
        || this.equals(ULONG)
        || this.equals(USHORT);
  }

  public final boolean isFloating() {
    return this.equals(FLOAT) || this.equals(DOUBLE);
  }

  public final boolean isPrimitive() {
    return this.equals(BOOLEAN)
        || this.equals(BYTE)
        || this.equals(CODE)
        || this.equals(SHORT)
        || this.equals(INT)
        || this.equals(LONG)
        || this.equals(FLOAT)
        || this.equals(DOUBLE)
        || this.equals(UBYTE)
        || this.equals(UINT)
        || this.equals(ULONG)
        || this.equals(USHORT);
  }

  public final boolean isSigned() {
    return this.equals(BOOLEAN)
        || this.equals(BYTE)
        ||
        // this.equals(CODE) ||
        this.equals(SHORT)
        || this.equals(INT)
        || this.equals(LONG);
  }

  public final boolean isUnsigned() {
    return this.equals(UBYTE)
        || this.equals(USHORT)
        || this.equals(CODE)
        || // right?
        this.equals(UINT)
        || this.equals(ULONG);
  }

  public final boolean isImmutable() {
    return getModifiers().isImmutable();
  }

  public boolean canAccept(
      Type rightType, AssignmentKind assignmentType, List<ShadowException> errors) {
    boolean accepts;

    // equal and cat are separate because they are not dependent on implementing a specific
    // interface
    if (assignmentType.equals(AssignmentKind.EQUAL)) {
      // type parameters are different because the definition of subtype is weak: dependent only on
      // the bounds
      // real type parameter assignment requires the same type
      accepts = rightType.isSubtype(this);

      if (!accepts) {
        if (rightType instanceof UnboundMethodType && this instanceof MethodReferenceType) {
          // adds appropriate errors (either ambiguous method or none matching)
          MethodType methodType = ((MethodReferenceType) this).getMethodType();
          rightType
              .getOuter()
              .getMatchingMethod(
                  rightType.getTypeName(), methodType.getParameterTypes(), errors);
        } else
          ErrorReporter.addError(
              errors,
              Error.INVALID_ASSIGNMENT,
              "Type " + rightType + " is not a subtype of " + this,
              rightType,
              this);
      }

      return accepts;
    } else if (assignmentType.equals(AssignmentKind.CAT)) {
      accepts = isString();
      if (!accepts)
        ErrorReporter.addError(
            errors, Error.INVALID_ASSIGNMENT, "Type " + this + " is not type " + Type.STRING, this);

      return accepts;
    }

    String methodName = assignmentType.getMethod();
    InterfaceType interfaceType;
    String operator = assignmentType.getOperator();

    switch (assignmentType) {
      case PLUS -> interfaceType = Type.CAN_ADD;
      case MINUS -> interfaceType = Type.CAN_SUBTRACT;
      case STAR -> interfaceType = Type.CAN_MULTIPLY;
      case SLASH -> interfaceType = Type.CAN_DIVIDE;
      case MOD -> interfaceType = Type.CAN_MODULUS;
      case AND, OR, XOR, LEFT_SHIFT, RIGHT_SHIFT, LEFT_ROTATE, RIGHT_ROTATE -> interfaceType = Type.INTEGER;
      default -> {
        return false;
      }
    }

    if (hasUninstantiatedInterface(interfaceType)) {
      SequenceType argument = new SequenceType(rightType);
      MethodSignature signature = getMatchingMethod(methodName, argument, errors);
      if (signature != null) {
        Type result = signature.getReturnTypes().getType(0);
        accepts = result.isSubtype(this);
        if (!accepts)
          ErrorReporter.addError(
              errors,
              Error.INVALID_ASSIGNMENT,
              "Type " + result + " is not a subtype of " + this,
              result,
              this);
        return accepts;
      } else return false;
    } else {
      ErrorReporter.addError(
          errors,
          Error.INVALID_TYPE,
          "Cannot apply operator "
              + operator
              + " to type "
              + this
              + " which does not implement interface "
              + interfaceType,
          this);
      return false;
    }
  }

  public MethodSignature getMatchingMethod(
      String methodName, SequenceType arguments) {
    List<ShadowException> errors = new ArrayList<>();
    return getMatchingMethod(methodName, arguments, errors);
  }


  public MethodSignature getMatchingMethod(
          String methodName, SequenceType arguments, SequenceType typeArguments) {
    List<ShadowException> errors = new ArrayList<>();
    return getMatchingMethod(methodName, arguments, typeArguments, errors);
  }

  public MethodSignature getMatchingMethod(
          String methodName,
          SequenceType arguments,
          List<ShadowException> errors) {
    return getMatchingMethod(methodName, arguments, null, errors);
  }

  // Type arguments are only needed for creates
  // TODO: Separate into code for methods and creates?
  public MethodSignature getMatchingMethod(
          String methodName,
          SequenceType arguments,
          SequenceType typeArguments,
          List<ShadowException> errors) {
    boolean hasTypeArguments = typeArguments != null;
    MethodSignature candidate = null;

    for (MethodSignature signature : recursivelyGetMethodOverloads(methodName)) {
      MethodType methodType = signature.getMethodType();

      if (methodType.isParameterized()) {
        if (hasTypeArguments) {
          SequenceType parameters = methodType.getTypeParameters();
          try {
            if (parameters.canAccept(typeArguments, SubstitutionKind.TYPE_PARAMETER)) {
              signature = signature.replace(parameters, typeArguments);
            } else continue;
          } catch (InstantiationException ignored) {
          }
        }
      }

      // the list of method signatures starts with the closest (current class) and then adds parents
      // and outer classes
      // always stick with the current if you can
      // (only replace if signature is a subtype of candidate but candidate is not a subtype of
      // signature)
      if (signature.canAccept(arguments)) {
        if (candidate == null
                || (signature.getParameterTypes().isSubtype(candidate.getParameterTypes())
                && !candidate.getParameterTypes().isSubtype(signature.getParameterTypes())))
          candidate = signature;
        else if (!candidate.getParameterTypes().isSubtype(signature.getParameterTypes())) {
          ErrorReporter.addError(
                  errors,
                  Error.INVALID_ARGUMENTS,
                  "Ambiguous reference to " + methodName + " with arguments " + arguments,
                  arguments);
          return null;
        }
      }
    }

    if (candidate == null)
      ErrorReporter.addError(
              errors,
              Error.INVALID_METHOD,
              "No definition of " + methodName + " with arguments " + arguments + " in this context",
              arguments);

    return candidate;
  }



  public Package getPackage() {
    return _package;
  }

  public List<Package> getAllPackages() {
    List<Package> packages = new ArrayList<>();
    Package current = _package;
    while (current != null) {
      packages.add(current);
      current = current.getParent();
    }

    return packages;
  }

  public void setPackage(Package p) {
    _package = p;
    invalidateHashName();
  }

  public SequenceType getTypeParameters() {
    return typeParameters;
  }

  public void addTypeParameter(ModifiedType parameter) {
    if (typeParameters == null) {
      typeParameters = new SequenceType();
      parameterized = true;
    }
    typeParameters.add(parameter);
    invalidateHashName();
  }

  public static String mangle(String name) {
    StringBuilder sb = new StringBuilder();

    for (char c : name.toCharArray()) {
      if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) sb.append(c);
      else if (c == '_') sb.append(c).append(c);
      else {
        sb.append("_U");
        for (int shift = 12; shift >= 0; shift -= 4)
          sb.append(Character.forDigit((c >> shift) & 0xf, 16));
      }
    }
    return sb.toString();
  }

  public void setParameterized(boolean value) {
    if (value && typeParameters == null) typeParameters = new SequenceType();

    if (!value) typeParameters = null;

    parameterized = value;
    invalidateHashName();
  }

  public boolean isParameterized() {
    return parameterized;
  }

  // Must have type parameters AND have them all filled in
  public boolean isFullyInstantiated() {
    if (!isParameterized()) return false;

    if (parameterized)
      for (ModifiedType parameter : typeParameters) {
        Type parameterType = parameter.getType();
        if (parameterType instanceof TypeParameter) return false;

        if (parameterType.isParameterized() && !parameterType.isFullyInstantiated()) return false;
      }

    return true;
  }

  public boolean isRecursivelyParameterized() {
    return isParameterized();
  }

  public boolean isUninstantiated() {
    return equals(getTypeWithoutTypeArguments());
  }

  /**
   * This method indicates whether a cast is necessary between two types.
   *
   * <p>Examples:
   *
   * <pre>
   *   STRING.isStrictSubtype(OBJECT) == true
   *   OBJECT.isStrictSubtype(OBJECT) == false
   *   OBJECT.isStrictSubtype(NULL) == false
   *   NULL.isStrictSubtype(OBJECT) == true
   *   NULL.isStrictSubtype(NULL) == false
   * </pre>
   *
   * @param other another type
   * @return {@literal true} if {@code this} can be cast to {@code other} and they are not equal
   */
  public boolean isStrictSubtype(Type other) {
    if (this == Type.NULL) return other != Type.NULL;
    if (equals(other)) return false;
    return isSubtype(other);
  }

  public void addTypeParameterDependency(Type type) {
    typeParameterDependencies.add(type);
  }

  public List<Type> getTypeParameterDependencies() {
    return typeParameterDependencies;
  }

  public boolean containsField(String fieldName) {
    return fieldTable.containsKey(fieldName);
  }

  public boolean containsConstant(String fieldName) {
    return constantTable.containsKey(fieldName);
  }

  public void addField(String fieldName, ShadowParser.VariableDeclaratorContext node) {
    fieldTable.put(fieldName, node);
    node.setEnclosingType(this);
  }

  public void addConstant(String fieldName, ShadowParser.VariableDeclaratorContext node) {
    constantTable.put(fieldName, node);
    node.setEnclosingType(this);
  }

  public ShadowParser.VariableDeclaratorContext getField(String fieldName) {
    return fieldTable.get(fieldName);
  }

  public ShadowParser.VariableDeclaratorContext recursivelyGetField(String fieldName) {
    ShadowParser.VariableDeclaratorContext context = getField(fieldName);
    if (context != null) return context;

    // Check outer types
    Type outer = this.getOuter();
    while (outer != null && context == null) {
      context = outer.getConstant(fieldName);
      outer = outer.getOuter();
    }

    if (context != null) return context;

    // Check interfaces
    for (InterfaceType interface_ : interfaces) {
      context = interface_.recursivelyGetField(fieldName);
      if (context != null) return context;
    }

    return null;
  }

  public ShadowParser.VariableDeclaratorContext getConstant(String fieldName) {
    return constantTable.get(fieldName);
  }

  public ShadowParser.VariableDeclaratorContext recursivelyGetConstant(String fieldName) {
    ShadowParser.VariableDeclaratorContext context = getConstant(fieldName);
    if (context != null) return context;

    // Check outer types
    Type outer = this.getOuter();
    while (outer != null && context == null) {
      context = outer.getConstant(fieldName);
      outer = outer.getOuter();
    }

    if (context != null) return context;

    // Check interfaces
    for (InterfaceType interface_ : interfaces) {
      context = interface_.recursivelyGetConstant(fieldName);
      if (context != null) return context;
    }

    return null;
  }

  public LinkedHashMap<String, ShadowParser.VariableDeclaratorContext> getFields() {
    return fieldTable;
  }

  public Map<String, ShadowParser.VariableDeclaratorContext> getConstants() {
    return constantTable;
  }

  public boolean containsIndistinguishableMethod(
      MethodSignature signature) { // not identical, but indistinguishable at call time
    List<MethodSignature> list = methodTable.get(signature.getSymbol());

    if (list != null)
      for (MethodSignature existing : list)
        if (existing.isIndistinguishable(signature)) return true;

    return false;
  }

  public void addMethod(MethodSignature signature) {
    // makes copy so that changing the outer type doesn't cause a problem
    signature.setOuter(this);
    String name = signature.getSymbol();

    if (methodTable.containsKey(name)) methodTable.get(name).add(signature);
    else {
      List<MethodSignature> list = new LinkedList<>();
      list.add(signature);
      methodTable.put(name, list);
    }
  }

  public Map<String, List<MethodSignature>> getMethodMap() {
    return methodTable;
  }

  public Set<MethodSignature> getAllMethods() {
    return methodTable.values().stream()
        .flatMap(Collection::stream)
        .collect(Collectors.toUnmodifiableSet());
  }

  public List<MethodSignature> getMethodOverloads(String methodName) {
    List<MethodSignature> signatures = methodTable.get(methodName);
    if (signatures == null) return new ArrayList<>();
    else return signatures;
  }

  protected void includeMethods(String methodName, List<MethodSignature> list) {
    for (MethodSignature signature : getMethodOverloads(methodName))
      if (!list.contains(signature)) list.add(signature);
  }

  private Map<MethodSignature, Integer> methodIndexCache;

  public int getMethodIndex(MethodSignature method) {
    // Lazily load cache
    if (methodIndexCache == null) {
      Map<MethodSignature, Integer> cache = new HashMap<>();
      List<MethodSignature> methods = orderAllMethods();
      for (int i = 0; i < methods.size(); i++) cache.put(methods.get(i), i);
      methodIndexCache = cache;
    }

    Integer index = methodIndexCache.get(method);
    return index == null ? -1 : index;
  }

  public List<MethodSignature> orderAllMethods() {
    return recursivelyOrderAllMethods(new ArrayList<>());
  }

  public List<MethodSignature> orderMethods() {
    return recursivelyOrderMethods(new ArrayList<>());
  }

  protected List<MethodSignature> orderMethods(List<MethodSignature> methodList, boolean add) {
    int parentSize = methodList.size();
    List<MethodSignature> result = add ? methodList : new ArrayList<>();
    List<MethodSignature> original = new ArrayList<>(methodList);
    for (List<MethodSignature> methods : new TreeMap<>(getMethodMap()).values())
      for (MethodSignature method : methods)
        if (!method.getModifiers().isPrivate()) {
          SequenceType parameters = method.getParameterTypes();
          SequenceType returns = method.getReturnTypes();
          boolean replaced = false;
          MethodSignature wrapper = method;
          for (int i = 0; i < parentSize; i++) {
            MethodSignature originalMethod = original.get(i);
            SequenceType originalParameters = originalMethod.getParameterTypes(),
                rawParameters =
                    originalMethod
                        .getSignatureWithoutTypeArguments()
                        .getMethodType()
                        .getParameterTypes();

            if ((!method.isCreate() || originalMethod.getOuter() instanceof InterfaceType)
                && method.getSymbol().equals(originalMethod.getSymbol())
                && parameters.size() == originalParameters.size()) {
              boolean replace = true, wrapped = false;
              if (!method.isCreate() && method.getOuter().isPrimitive()) wrapped = true;
              for (int j = 0; replace && j < parameters.size(); j++) {
                ModifiedType parameter = parameters.get(j),
                    originalParameter = originalParameters.get(j),
                    rawParameter = rawParameters.get(j);

                // can be broader than original types
                if (!originalParameter.getType().isSubtype(parameter.getType())) replace = false;
                else if (getWidth(parameter) != getWidth(rawParameter)) wrapped = true;
              }

              // adding wrapping for returns as well
              SequenceType originalReturns = originalMethod.getReturnTypes(),
                  rawReturns =
                      originalMethod
                          .getSignatureWithoutTypeArguments()
                          .getMethodType()
                          .getReturnTypes();
              for (int j = 0; replace && j < returns.size(); j++) {
                ModifiedType returnValue = returns.get(j),
                    originalReturn = originalReturns.get(j),
                    rawReturn = rawReturns.get(j);
                // can be narrower than original types
                if (!returnValue.getType().isSubtype(originalReturn.getType()))
                  // if ( !parentReturn.getType().isSubtype(returnValue.getType()) )
                  replace = false;
                else if (getWidth(returnValue) != getWidth(rawReturn)) wrapped = true;
              }

              if (replace) {
                // we've found a replacement method, but it has to be the tightest replacement
                // possible
                MethodSignature currentMethod = methodList.get(i);
                if (currentMethod == originalMethod
                    || currentMethod.getMethodType().isSubtype(method.getMethodType())) {
                  replaced = true;
                  if (wrapped && wrapper == method) wrapper = originalMethod.wrap(method);
                  methodList.set(i, wrapper);
                }
              }
            }
          }
          if (wrapper != method) {
            if (!add) result.add(wrapper);
            result.add(method);
          } else if (!add || !replaced)
            if (!method.isCreate() || method.getOuter() instanceof InterfaceType)
              result.add(method);
        }
    return result;
  }

  /**
   * This function is only used for error reporting as it finds an indistinguishable signature.
   *
   * @param signature signature to test against
   * @return signature that is indistinguishable from the parameter
   */
  public MethodSignature getIndistinguishableMethod(MethodSignature signature) {
    for (MethodSignature ms : methodTable.get(signature.getSymbol())) {
      if (ms.isIndistinguishable(signature)) return ms;
    }

    return null;
  }

  public boolean encloses(Type type) {
    if (getTypeWithoutTypeArguments().equals(type.getTypeWithoutTypeArguments())) return true;

    Type outer = type.getOuter();
    if (outer == null || type instanceof ArrayType) return false;

    return encloses(outer);
  }

  public boolean canSee(Type type) {
    Type currentRawType = this.getTypeWithoutTypeArguments();
    boolean visible =
        BaseChecker.typeIsAccessible(type.getTypeWithoutTypeArguments(), currentRawType);

    if (type.isParameterized()) {
      List<ModifiedType> parameters = type.getTypeParameters();
      for (int i = 0; i < parameters.size() && visible; ++i)
        visible = currentRawType.canSee(parameters.get(i).getType());
    }

    return visible;
  }

  public void addMentionedType(Type type) {
    if (type == null || type instanceof UninstantiatedType || type instanceof TypeParameter) return;

    if (type instanceof ArrayType arrayType) {
      Type baseType = arrayType.getBaseType();
      addMentionedType(baseType);
    } else if (type instanceof MethodType methodType) {
      for (ModifiedType parameter : methodType.getParameterTypes())
        addMentionedType(parameter.getType());

      for (ModifiedType _return : methodType.getReturnTypes()) addMentionedType(_return.getType());
    } else if ((type instanceof ClassType) || (type instanceof InterfaceType))
      mentionedTypes.add(type);
  }

  // Returns true only if this uses *some* type parameters from type (but not any parameters from
  // other types)
  protected boolean onlyUsesTypeParametersFrom(Type type) {
    if (type.isParameterized() && isParameterized() && !isFullyInstantiated()) {
      Set<TypeParameter> parameters = new HashSet<>();

      for (ModifiedType modifiedType : type.getTypeParameters()) {
        Type parameter = modifiedType.getType();
        if (parameter instanceof TypeParameter) parameters.add((TypeParameter) parameter);
      }

      return onlyUsesTheseParameters(parameters);
    }

    return false;
  }

  // Returns true if this uses no parameters or only these parameters
  protected boolean onlyUsesTheseParameters(Set<TypeParameter> parameters) {
    if (isParameterized()) {
      if (isFullyInstantiated()) return true;

      for (ModifiedType modifiedType : getTypeParameters()) {
        Type parameter = modifiedType.getType();
        if (parameter instanceof TypeParameter typeParameter) {
          if (!parameters.contains(typeParameter)) return false;
        } else if (!parameter.onlyUsesTheseParameters(parameters)) return false;
      }
    }

    return true;
  }

  public Set<Type> getInstantiatedGenerics() {
    Set<Type> genericClasses = new HashSet<>();
    TreeSet<Type> startingClasses = new TreeSet<>(getUsedTypes());

    // find all generics that need to be written
    // start with all generic types used by the module
    // then add their dependencies (and their dependencies, etc.)
    while (!startingClasses.isEmpty()) {
      Type type = startingClasses.first();
      startingClasses.remove(type);

      if ((type instanceof ArrayType && !((ArrayType) type).containsUnboundTypeParameters())
              || (type.isFullyInstantiated()
              && !type.getTypeWithoutTypeArguments().equals(Type.ARRAY)
              && !type.getTypeWithoutTypeArguments().equals(Type.ARRAY_NULLABLE))) {
        genericClasses.add(type);

        SequenceType dependencies = null;

        if (type instanceof ArrayType)
          dependencies = ((ArrayType) type).convertToGeneric().getDependencyList();
        else if (type instanceof ClassType) dependencies = ((ClassType) type).getDependencyList();

        if (dependencies != null)
          for (ModifiedType modifiedType : dependencies) {
            Type dependency = modifiedType.getType();
            // arrays are in their "generic" form and should be turned back
            if (dependency.getTypeWithoutTypeArguments().equals(Type.ARRAY))
              dependency = new ArrayType(dependency.getTypeParameters().getType(0));
            else if (dependency.getTypeWithoutTypeArguments().equals(Type.ARRAY_NULLABLE))
              dependency = new ArrayType(dependency.getTypeParameters().getType(0), true);

            if (genericClasses.add(dependency))
              startingClasses.add(dependency);
          }
      }
    }

    return genericClasses;
  }

  public void addUsedType(Type type) {
    if (type == null || type instanceof UninstantiatedType) return;

    if (!usedTypes.contains(type)) {
      if (type instanceof TypeParameter typeParameter) {
        for (Type bound : typeParameter.getBounds()) addUsedType(bound);
      } else if (type instanceof ArrayType arrayType) {
        Type baseType = arrayType.getBaseType();

        usedTypes.add(type);

        // Covers Type.ARRAY and all recursive base types
        // automatically does the right thing for NullableArray
        // must do before adding to usedTypes
        addUsedType(arrayType.convertToGeneric());

        addUsedType(baseType);
      } else if (type instanceof MethodReferenceType)
        addUsedType(((MethodReferenceType) type).getMethodType());
      else if (type instanceof MethodType methodType) {
        for (ModifiedType parameter : methodType.getParameterTypes())
          addUsedType(parameter.getType());

        for (ModifiedType _return : methodType.getReturnTypes()) addUsedType(_return.getType());
      } else if ((type instanceof ClassType) || (type instanceof InterfaceType)) {
        usedTypes.add(type);

        if (type.isParameterized()) {
          usedTypes.add(type.typeWithoutTypeArguments);

          for (ModifiedType typeParameter : type.getTypeParameters()) {
            Type parameterType = typeParameter.getType();
            addUsedType(parameterType);
            if ((parameterType instanceof ArrayType)
                && (parameterType.isFullyInstantiated() || !parameterType.isParameterized()))
              usedTypes.add(typeParameter.getType()); // directly add array type parameter
          }
        }

        // Interface classes are often needed "invisibly" in order to perform casts
        if (type instanceof InterfaceType) addPartiallyInstantiatedClass(type);
        else {
          // Add parent types
          ClassType classType = (ClassType) type;
          if (classType.getExtendType() != null) addUsedType(classType.getExtendType());
        }
      }

      // References to inner types
      for (Type inner : type.getInnerTypes().values()) addUsedType(inner);

      // Add reference to outer types
      Type outer = getOuter();
      while (outer != null) {
        outer.addUsedType(type);
        outer = outer.getOuter();
      }

      // Add interfaces
      ArrayList<InterfaceType> interfaces = type.getInterfaces();
      for (InterfaceType interfaceType : interfaces) addUsedType(interfaceType);

      /* Add methods and fields to mentioned types
       * These do not need to be filled out later unless they are also used types.
       */
      for (List<MethodSignature> methodList : type.getMethodMap().values())
        for (MethodSignature signature : methodList) {
          MethodType methodType = signature.getMethodType();
          for (ModifiedType parameter : methodType.getParameterTypes())
            addMentionedType(parameter.getType());

          for (ModifiedType _return : methodType.getReturnTypes())
            addMentionedType(_return.getType());
        }

      for (Context node : type.getFields().values()) addMentionedType(node.getType());
    }
  }

  public Set<Type> getUsedTypes() {
    return usedTypes;
  }

  public Set<Type> getMentionedTypes() {
    return mentionedTypes;
  }

  public Set<Type> getPartiallyInstantiatedClasses() {
    return partiallyInstantiatedClasses;
  }

  public void addPartiallyInstantiatedClass(Type type) {
    if (type instanceof ArrayType) type = ((ArrayType) type).convertToGeneric();

    if (type.onlyUsesTypeParametersFrom(this) && !type.equals(this))
      partiallyInstantiatedClasses.add(type);
  }

  public boolean hasInterface(InterfaceType type) {
    return false;
  }

  public boolean hasUninstantiatedInterface(InterfaceType type) {
    return false;
  }

  public void addInterface(InterfaceType implementType) {
    interfaces.add(implementType);
  }

  public ArrayList<InterfaceType> getInterfaces() {
    return interfaces;
  }

  public void setInterfaces(ArrayList<InterfaceType> values) {
    interfaces = values;
  }

  // must return an ArrayList to preserve order
  // it is essential that generic classes list their interfaces in the same order as each other
  // otherwise the corresponding blocks of methods won't match
  // the set is used to prevent duplicates
  public ArrayList<InterfaceType> getAllInterfaces() {
    HashSet<InterfaceType> set = new HashSet<>();
    ArrayList<InterfaceType> list = new ArrayList<>();

    for (InterfaceType interfaceType : getInterfaces()) {
      for (InterfaceType type : interfaceType.getAllInterfaces()) {
        if (set.add(type)) list.add(type);
      }
    }

    return list;
  }

  public boolean isDescendantOf(Type type) {
    return false;
  }

  protected List<MethodSignature> recursivelyOrderMethods(List<MethodSignature> methodList) {
    throw new UnsupportedOperationException();
  }

  protected List<MethodSignature> recursivelyOrderAllMethods(List<MethodSignature> methodList) {
    throw new UnsupportedOperationException();
  }

  public void printMetaFile(PrintWriter out, String linePrefix) {
    throw new UnsupportedOperationException();
  }

  /** Returns all overloads for the given method found both in this type and its outer types */
  public List<MethodSignature> recursivelyGetMethodOverloads(String methodName) {
    throw new UnsupportedOperationException();
  }

  public abstract boolean isSubtype(Type other);

  public abstract Type replace(List<ModifiedType> values, List<ModifiedType> replacements)
      throws InstantiationException;

  public abstract Type partiallyReplace(List<ModifiedType> values, List<ModifiedType> replacements)
      throws InstantiationException;

  public abstract void updateFieldsAndMethods() throws InstantiationException;

  public Map<String, ImportInformation> getImportedItems() {
    return Collections.unmodifiableMap(importedItems);
  }

  public void addImportedItem(String name, ImportInformation importInfo) {
    importedItems.put(name, importInfo);
  }

  @Override
  public final int compareTo(Type other) {
    return getHashName().compareTo(other.getHashName());
  }

  protected final void printImports(PrintWriter out, String linePrefix) {
    if (getOuter() == null) {

      for (ImportInformation information : importedItems.values()) {
        Type type = information.getType();
        if (type.getOuter() == null && type != this)
          out.println(linePrefix + "import " + type.toString(PACKAGES) + ";");
      }

      out.println();
    }
  }

  public boolean hasDocumentation() {
    return (documentation != null);
  }

  public Documentation getDocumentation() {
    return documentation;
  }

  public void setDocumentation(Documentation documentation) {
    this.documentation = documentation;
  }

  private static SequenceType exceptionType = null;

  public static SequenceType getExceptionType() {
    if (exceptionType == null) {
      exceptionType = new SequenceType();
      exceptionType.add(new SimpleModifiedType(new PointerType()));
      exceptionType.add(new SimpleModifiedType(Type.INT));
    }
    return exceptionType;
  }
}
