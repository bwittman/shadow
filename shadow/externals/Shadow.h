#ifndef SHADOW_H
#define SHADOW_H

// boolean
typedef char ShadowBoolean;

// byte
typedef signed char ShadowByte;

// ubyte
typedef unsigned char ShadowUByte;

// short
typedef signed short ShadowShort;

// ushort
typedef unsigned short ShadowUShort;

// int
typedef signed long ShadowInt;

// uint
typedef unsigned long ShadowUInt;

// code
typedef signed long ShadowCode;

// long
typedef signed long long ShadowLong;

// ulong
typedef unsigned long long ShadowULong;

// float
typedef float ShadowFloat;

// double
typedef double ShadowDouble;

#include "include/ShadowPointer.h"

#include "include/ShadowMutex.h"
#include "include/ShadowThread.h"

#endif