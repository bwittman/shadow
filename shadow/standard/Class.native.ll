; shadow.standard@Class native methods

%boolean = type i1
%byte = type i8
%ubyte = type i8
%short = type i16
%ushort = type i16
%int = type i32
%uint = type i32
%code = type i32
%long = type i64
%ulong = type i64
%float = type float
%double = type double

declare i32 @__shadow_personality_v0(...)
declare void @__shadow_throw(%shadow.standard..Object*) noreturn
declare %shadow.standard..Exception* @__shadow_catch(i8* nocapture) nounwind
declare i32 @llvm.eh.typeid.for(i8*) nounwind readnone

; standard definitions
%shadow.standard..Object_methods = type { %shadow.standard..Object* (%shadow.standard..Object*, %shadow.standard..AddressMap*)*, void (%shadow.standard..Object*)*, %shadow.standard..Class* (%shadow.standard..Object*)*, %shadow.standard..String* (%shadow.standard..Object*)* }
%shadow.standard..Object = type { %ulong, %shadow.standard..Class*, %shadow.standard..Object_methods*  }
%shadow.standard..Class_methods = type opaque
%shadow.standard..Class = type { %ulong, %shadow.standard..Class*, %shadow.standard..Class_methods* , %shadow.standard..String*, %shadow.standard..Class*, {{%ulong, %shadow.standard..MethodTable*}*, %shadow.standard..Class*,  %ulong }, {{%ulong, %shadow.standard..Class*}*, %shadow.standard..Class*, %ulong }, %int, %int }
%shadow.standard..GenericClass_methods = type opaque
%shadow.standard..GenericClass = type { %ulong, %shadow.standard..Class*, %shadow.standard..GenericClass_methods* , %shadow.standard..String*, %shadow.standard..Class*, {{%ulong, %shadow.standard..MethodTable*}*, %shadow.standard..Class*,  %ulong }, {{%ulong, %shadow.standard..Class*}*, %shadow.standard..Class*, %ulong }, %int, %int, {{%ulong, %shadow.standard..Class*}*, %shadow.standard..Class*, %ulong }, {{%ulong, %shadow.standard..MethodTable*}*, %shadow.standard..Class*,  %ulong } }
%shadow.standard..Iterator_methods = type opaque
%shadow.standard..String_methods = type opaque
%shadow.standard..String = type { %ulong, %shadow.standard..Class*, %shadow.standard..String_methods* , {{%ulong, %byte}*, %shadow.standard..Class*, %ulong }, %boolean }
%shadow.standard..AddressMap_methods = type opaque
%shadow.standard..AddressMap = type opaque
%shadow.standard..MethodTable_methods = type opaque
%shadow.standard..MethodTable = type opaque

%shadow.standard..Exception_methods = type opaque
%shadow.standard..Exception = type { %ulong, %shadow.standard..Class*, %shadow.standard..Exception_methods* , %shadow.standard..String* }
%shadow.standard..OutOfMemoryException_methods = type opaque
%shadow.standard..OutOfMemoryException = type { %ulong, %shadow.standard..Class*, %shadow.standard..OutOfMemoryException_methods* , %shadow.standard..String* }

@shadow.standard..Object_class = external constant %shadow.standard..Class
@shadow.standard..Class_methods = external constant %shadow.standard..Class_methods
@shadow.standard..Class_class = external constant %shadow.standard..Class
@shadow.standard..String_methods = external constant %shadow.standard..String_methods
@shadow.standard..String_class = external constant %shadow.standard..Class
@shadow.standard..byte_class = external constant %shadow.standard..Class
@shadow.standard..Exception_methods = external constant %shadow.standard..Exception_methods
@shadow.standard..Exception_class = external constant %shadow.standard..Class
@shadow.standard..OutOfMemoryException_class = external constant %shadow.standard..Class
@shadow.standard..OutOfMemoryException_methods = external constant %shadow.standard..OutOfMemoryException_methods
@shadow.standard..MethodTable_class = external constant %shadow.standard..Class

declare noalias i8* @calloc(i64, i64) nounwind
declare void @free(i8*) nounwind
declare %shadow.standard..OutOfMemoryException* @shadow.standard..OutOfMemoryException_Mcreate(%shadow.standard..Object*)
declare %int @shadow.standard..Class_Mwidth(%shadow.standard..Class*)
declare void @shadow.standard..Object_Mdestroy(%shadow.standard..Object*)

%shadow.io..Console_methods = type opaque
%shadow.io..Console = type { %ulong, %shadow.standard..Class*, %shadow.io..Console_methods* , %boolean }

declare %shadow.io..Console* @shadow.io..Console_Mprint_shadow.standard..String(%shadow.io..Console*, %shadow.standard..String*)
declare %shadow.io..Console* @shadow.io..Console_Mprint_shadow.standard..Object(%shadow.io..Console*, %shadow.standard..Object*)
declare %shadow.io..Console* @shadow.io..Console_MprintLine(%shadow.io..Console*) 
declare %shadow.io..Console* @shadow.io..Console_MdebugPrint_int(%shadow.io..Console*, %int)


define %int @shadow.standard..Class_MarraySize(%shadow.standard..Class*) alwaysinline nounwind readnone {
		%2 = ptrtoint {%shadow.standard..Class*, %shadow.standard..Object*, %ulong}* getelementptr ({%shadow.standard..Class*, %shadow.standard..Object*, %ulong}, {%shadow.standard..Class*, %shadow.standard..Object*, %ulong}* null, i32 1) to i32
		ret %int %2
}

define %int @shadow.standard..Class_MpointerSize(%shadow.standard..Class*) alwaysinline nounwind readnone {
		%2 = ptrtoint %shadow.standard..Object** getelementptr (%shadow.standard..Object*, %shadow.standard..Object** null, i32 1) to i32
		ret %int %2
}


define noalias %shadow.standard..Object* @__allocate(%shadow.standard..Class* %class, %shadow.standard..Object_methods* %methods) {	
	%sizeRef = getelementptr inbounds %shadow.standard..Class, %shadow.standard..Class* %class, i32 0, i32 8
	%size = load %uint, %uint* %sizeRef	
	%sizeLong = zext %uint %size to %ulong
	
	%memory = call noalias i8* @calloc(%ulong 1, %ulong %sizeLong) nounwind
	%isNull = icmp eq i8* %memory, null
	br i1 %isNull, label %_outOfMemory, label %_success
_outOfMemory: 
	%exception = bitcast %shadow.standard..OutOfMemoryException* @_OutOfMemoryException to %shadow.standard..Object*
	call void @__shadow_throw(%shadow.standard..Object* %exception) noreturn
    unreachable
_success:
	%object = bitcast i8* %memory to %shadow.standard..Object*
	; set reference count
	%countRef = getelementptr inbounds %shadow.standard..Object, %shadow.standard..Object* %object, i32 0, i32 0
	store %ulong 1, %ulong* %countRef		
	%object.class = getelementptr inbounds %shadow.standard..Object, %shadow.standard..Object* %object, i32 0, i32 1
    store %shadow.standard..Class* %class, %shadow.standard..Class** %object.class
    %object.methods = getelementptr inbounds %shadow.standard..Object, %shadow.standard..Object* %object, i32 0, i32 2
    store %shadow.standard..Object_methods* %methods, %shadow.standard..Object_methods** %object.methods
	
	ret %shadow.standard..Object* %object
}

define noalias {%ulong, %shadow.standard..Object*}* @__allocateArray(%shadow.standard..Class* %class, %ulong %elements) {	
	%perObject = call %int @shadow.standard..Class_Mwidth(%shadow.standard..Class* %class)	
	%perObjectLong = zext %int %perObject to %ulong
	%size = mul %ulong %perObjectLong, %elements
	
	; Add extra room for reference count (stored before array space)
	%sizeWithCounter = add %ulong %size, 8	
	%arrayAsBytes = call noalias i8* @calloc(%ulong 1, %ulong %sizeWithCounter)	
	%isNull = icmp eq i8* %arrayAsBytes, null
	br i1 %isNull, label %_outOfMemory, label %_success
_outOfMemory:
	%exception = bitcast %shadow.standard..OutOfMemoryException* @_OutOfMemoryException to %shadow.standard..Object*
	call void @__shadow_throw(%shadow.standard..Object* %exception) noreturn
	unreachable
_success:
	; store reference count of 1
	%array = bitcast i8* %arrayAsBytes to {%ulong, %shadow.standard..Object*}*	
	%countRef = getelementptr {%ulong, %shadow.standard..Object*}, {%ulong, %shadow.standard..Object*}* %array, i32 0, i32 0
	store %ulong 1, %ulong* %countRef		
	ret {%ulong, %shadow.standard..Object*}* %array
}

define void @__decrementRef(%shadow.standard..Object* %object) nounwind {	

	%isNull = icmp eq %shadow.standard..Object* %object, null
	br i1 %isNull, label %_exit, label %_check
_check:
	; get reference count
	%countRef = getelementptr inbounds %shadow.standard..Object, %shadow.standard..Object* %object, i32 0, i32 0
	%count = load %ulong, %ulong* %countRef
	; check if reference count is not int max (marks non-gc objects)  (unsigned -1 is int max)
	%isGC = icmp ne %ulong %count, -1
	br i1 %isGC, label %_checkPassed, label %_exit
_checkPassed:
	; atomically decrease reference count by one and get old value
	%oldCount = atomicrmw sub %ulong* %countRef, %ulong 1 acquire
	; if old count was 1, call destroy and deallocate (prevents double free in multithreaded situations)
	%free = icmp eq %ulong %oldCount, 1
	br i1 %free, label %_freeLabel, label %_exit
_freeLabel:	
	; call destroy before free	
	%methodsRef = getelementptr inbounds %shadow.standard..Object, %shadow.standard..Object* %object, i32 0, i32 2
    %methods = load %shadow.standard..Object_methods*, %shadow.standard..Object_methods** %methodsRef
    %destroyRef = getelementptr inbounds %shadow.standard..Object_methods, %shadow.standard..Object_methods* %methods, i32 0, i32 1
    %destroy = load void (%shadow.standard..Object*)*, void (%shadow.standard..Object*)** %destroyRef

    call void %destroy(%shadow.standard..Object* %object)
	
	; free	
	%address = bitcast %shadow.standard..Object* %object to i8*
	tail call void @free(i8* %address) nounwind
	ret void
_exit:	
	ret void
}

define void @__incrementRef(%shadow.standard..Object* %object) nounwind {
	
	%isNull = icmp eq %shadow.standard..Object* %object, null
	br i1 %isNull, label %_exit, label %_check
_check:
	; get reference count
	%countRef = getelementptr inbounds %shadow.standard..Object, %shadow.standard..Object* %object, i32 0, i32 0
	%count = load %ulong, %ulong* %countRef
	; check if reference count is not ulong max (marks non-gc objects)  (unsigned -1 is ulong max)
	%isGC = icmp ne %ulong %count, -1
	br i1 %isGC, label %_checkPassed, label %_exit
_checkPassed:
	; atomically increase reference count by one
	atomicrmw add %ulong* %countRef, %ulong 1 acquire
	ret void
_exit:		
	ret void
}

define void @__incrementRefArray({%ulong, %shadow.standard..Object*}* %array) nounwind {	

	%isNull = icmp eq {%ulong, %shadow.standard..Object*}* %array, null
	br i1 %isNull, label %_exit, label %_check
_check:
	%countRef = getelementptr {%ulong, %shadow.standard..Object*}, {%ulong, %shadow.standard..Object*}* %array, i32 0, i32 0
	%count = load %ulong, %ulong* %countRef
	; check if reference count is not ulong max (marks non-gc objects)  (unsigned -1 is ulong max)
	%isGC = icmp ne %ulong %count, -1
	br i1 %isGC, label %_checkPassed, label %_exit
_checkPassed:
	; atomically increase reference count by one
	atomicrmw add %ulong* %countRef, %ulong 1 acquire
	ret void
_exit:		
	ret void
}

define void @__decrementRefArray({{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}* %arrayPtr) nounwind {
	
	%isNull = icmp eq {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}* %arrayPtr, null
	br i1 %isNull, label %_exit, label %_check1
_check1:
	%countAndArrayRef = getelementptr {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}, {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}* %arrayPtr, i32 0, i32 0
	%countAndArray = load {%ulong, %shadow.standard..Object*}*, {%ulong, %shadow.standard..Object*}** %countAndArrayRef
	%arrayNull = icmp eq {%ulong, %shadow.standard..Object*}* %countAndArray, null
	br i1 %arrayNull, label %_exit, label %_check2	
_check2:	
	%countRef = getelementptr {%ulong, %shadow.standard..Object*}, {%ulong, %shadow.standard..Object*}* %countAndArray, i32 0, i32 0
	%count = load %ulong, %ulong* %countRef
	; check if reference count is not ulong max (marks non-gc objects)  (unsigned -1 is ulong max)
	%isGC = icmp ne %ulong %count, -1
	br i1 %isGC, label %_checkPassed, label %_exit
_checkPassed:
	; get reference count (stored before array)
	; decrease by one and get old value
	%oldCount = atomicrmw sub %ulong* %countRef, %ulong 1 acquire
	; if old value was 1, call destroy and deallocate (prevents double free in multithreaded situations)
	%free = icmp eq %ulong %oldCount, 1
	br i1 %free, label %_checkLength, label %_exit
_checkLength:
	%array = getelementptr {%ulong, %shadow.standard..Object*}, {%ulong, %shadow.standard..Object*}* %countAndArray, i32 0, i32 1
	%sizeRef = getelementptr inbounds {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}, {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}* %arrayPtr, i32 0, i32 2
	%size = load i64, i64* %sizeRef
	%check.length = icmp ne i64 %size, 0
	br i1 %check.length, label %_getClass, label %_freeArray
_getClass:	
	; here's where things change from objects
	%baseRef = getelementptr {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}, {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}* %arrayPtr, i32 0, i32 1
	%base = load %shadow.standard..Class*, %shadow.standard..Class** %baseRef
	%flagRef = getelementptr inbounds %shadow.standard..Class, %shadow.standard..Class* %base, i32 0, i32 7	
	%flag = load i32, i32* %flagRef
	%primitiveFlag = and i32 %flag, 2	
	%notPrimitive = icmp eq i32 %primitiveFlag, 0
	%notMethodTable = icmp ne %shadow.standard..Class* %base, @shadow.standard..MethodTable_class
	%notPrimitiveOrMethodTable = and i1 %notPrimitive, %notMethodTable	
	; if primitive or method table elements, no elements to decrement
	br i1 %notPrimitiveOrMethodTable, label %_checkFlag, label %_freeArray	

	; see if it's an array of arrays
_checkFlag:
	%arrayFlag = and i32 %flag, 8	
	%notArray = icmp eq i32 %arrayFlag, 0
	br i1 %notArray, label %_checkInterface, label %_array
	
_checkInterface:
	%interfaceFlag = and i32 %flag, 1
	%notInterface = icmp eq i32 %interfaceFlag, 0
	br i1 %notInterface, label %_object, label %_interface	
	
	; array of objects to free
	; loop through and decrement
_object:
	%i.3 = phi i64 [0, %_checkInterface], [%i.4, %_object]
	%element.1.ref = getelementptr inbounds %shadow.standard..Object*, %shadow.standard..Object** %array, i64 %i.3	
	%element.1 = load %shadow.standard..Object*, %shadow.standard..Object** %element.1.ref		
	call void @__decrementRef(%shadow.standard..Object* %element.1)
	%i.4 = add i64 %i.3, 1
	%check.i4 = icmp ult i64 %i.4, %size
	br i1 %check.i4, label %_object, label %_freeArray

	; array of arrays to free
_array:
	; width of each element in bytes	
	%arrayElements = bitcast %shadow.standard..Object** %array to {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}*
	br label %_arrayLoop
	
	; loop through and decrement array elements (which are also arrays)
_arrayLoop:
	%i.5 = phi i64 [0, %_array], [%i.6, %_arrayLoop]	
	%element.2 = getelementptr inbounds {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}, {{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}* %arrayElements, i64 %i.5
	call void @__decrementRefArray({{%ulong, %shadow.standard..Object*}*, %shadow.standard..Class*, %ulong}* %element.2)
	%i.6 = add i64 %i.5, 1
	%check.i6 = icmp ult i64 %i.6, %size
	br i1 %check.i6, label %_arrayLoop, label %_freeArray
		
	; array of interfaces to free
_interface:
	%elements = bitcast %shadow.standard..Object** %array to {%shadow.standard..MethodTable*, %shadow.standard..Object*}*
	br label %_interfaceLoop
_interfaceLoop:
	%i.7 = phi i64 [0, %_interface], [%i.8, %_interfaceLoop]
	%element.3.ref = getelementptr inbounds {%shadow.standard..MethodTable*, %shadow.standard..Object*}, {%shadow.standard..MethodTable*, %shadow.standard..Object*}* %elements, i64 %i.7	
	%element.3 = load {%shadow.standard..MethodTable*, %shadow.standard..Object*}, {%shadow.standard..MethodTable*, %shadow.standard..Object*}* %element.3.ref		
	%elementObject = extractvalue {%shadow.standard..MethodTable*, %shadow.standard..Object*} %element.3, 1
	call void @__decrementRef(%shadow.standard..Object* %elementObject)
	%i.8 = add i64 %i.7, 1
	%check.i8 = icmp ult i64 %i.8, %size
	br i1 %check.i8, label %_interfaceLoop, label %_freeArray

_freeArray:
	%address = bitcast %ulong* %countRef to i8*
	tail call void @free(i8* %address) nounwind	
	ret void
_exit:	
	ret void
}

@_array0 = private unnamed_addr constant {%ulong, [20 x %byte] } {%ulong -1, [20 x %byte] c"Heap space exhausted"}
@_string0 = private unnamed_addr constant %shadow.standard..String { %ulong -1, %shadow.standard..Class* @shadow.standard..String_class, %shadow.standard..String_methods* @shadow.standard..String_methods, { { %ulong, %byte }*, %shadow.standard..Class*, %ulong } { { %ulong, %byte }* bitcast ({%ulong, [20 x %byte]}* @_array0 to { %ulong, %byte }* ), %shadow.standard..Class* @shadow.standard..byte_class, %ulong 20}, %boolean true }
@_OutOfMemoryException = private constant %shadow.standard..OutOfMemoryException { %ulong -1, %shadow.standard..Class* @shadow.standard..OutOfMemoryException_class, %shadow.standard..OutOfMemoryException_methods* @shadow.standard..OutOfMemoryException_methods, %shadow.standard..String* @_string0 }