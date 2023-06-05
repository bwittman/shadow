/**
 * Author: Claude Abounegm
 */
#include <Shadow.h>
#include <standard/Thread.h>
#include <stddef.h>
#include <stdlib.h>

#ifdef SHADOW_WINDOWS
	#include <Windows.h>
#else
    #include <pthread.h>
#endif

// METHOD SIGNATURES //
//*HELPERS*
/**
 * The method that will be execute in the new spawned thread.
 * This is a wrapper which calls the actual thread start on the
 * Shadow side. It is here to allow the Thread.native.ll to set
 * the current Thread-Local-Storage thread to the newly spawned
 * thread.
**/ 
void* _shadow_standard__Thread_start(shadow_Thread_t*);
// METHOD SIGNATURES //

shadow_Pointer_t* __shadow_standard__Thread_spawn(shadow_Thread_t* _this)
{
#ifdef SHADOW_WINDOWS
    HANDLE* handle = malloc(sizeof(HANDLE));
    *handle = CreateThread(NULL, 0, (unsigned long (*)(void *))_shadow_standard__Thread_start, _this, 0, NULL);

	if(*handle == NULL) {
		free(handle);
		handle = NULL;
	}

	return _shadow_natives__Pointer_create(handle, SHADOW_CAN_FREE);
#else
    pthread_t* ptr = malloc(sizeof(pthread_t));
	if(pthread_create(ptr, NULL, _shadow_standard__Thread_start, _this) != 0) {
		free(ptr);
		ptr = NULL;
	}

	return _shadow_natives__Pointer_create(ptr, SHADOW_CAN_FREE);
#endif
}