/* AUTO-GENERATED FILE, DO NOT EDIT! */
#include "shadow/standard/String.meta"

static struct _Pshadow_Pstandard_CString _Istring0 = {
     &_Pshadow_Pstandard_CString_Imethods,
     (boolean_t)1, (ubyte_t *)"shadow.standard@String"
};
struct _Pshadow_Pstandard_CClass _Pshadow_Pstandard_CString_Iclass = {
     &_Pshadow_Pstandard_CClass_Imethods, &_Istring0
};

struct _Pshadow_Pstandard_CString* _Pshadow_Pstandard_CString_MtoString(struct _Pshadow_Pstandard_CString* this) {
     return this;                                                                /* (37:9) */
}

void _Pshadow_Pstandard_CString_Mconstructor(struct _Pshadow_Pstandard_CString* this) {
     this->_Imethods = &_Pshadow_Pstandard_CString_Imethods;
     this->ascii = 0;                                                            /* (7:9) */
     this->data = ((void *)0);                                                   /* (6:9) */
}

struct _Pshadow_Pstandard_CClass* _Pshadow_Pstandard_CString_MgetClass(struct _Pshadow_Pstandard_CString* this) {
     return &_Pshadow_Pstandard_CString_Iclass;
}

struct _Pshadow_Pstandard_CString_Itable _Pshadow_Pstandard_CString_Imethods = {
     _Pshadow_Pstandard_CString_MgetClass,
     _Pshadow_Pstandard_CString_MtoString,
};
