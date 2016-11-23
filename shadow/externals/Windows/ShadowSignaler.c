#include <stddef.h>
#include <pthread.h>
#include <stdlib.h>
#include "../Shadow.h"

#define NANOS_IN_SECONDS 1000000000LL

typedef struct {
	pthread_cond_t cond;
	pthread_mutex_t mutex;
} CondData;

static void InvalidatePointer(CondData** ptr) 
{
	free(*ptr);
	*ptr = NULL;
}

ShadowPointer __ShadowSignaler_Initialize()
{
	CondData* ptr = malloc(sizeof(CondData));
	
	if(pthread_cond_init(&ptr->cond, NULL) != 0) {
		InvalidatePointer(&ptr);
	} else if(pthread_mutex_init(&ptr->mutex, NULL) != 0) {
		pthread_cond_destroy(&ptr->mutex);
		InvalidatePointer(&ptr);
	}
	
	return __createShadowPointer(ptr);
}

ShadowBoolean __ShadowSignaler_Destroy(ShadowPointer shadowPtr)
{
	CondData* ptr = __extractRawPointer(shadowPtr);
	
	int result = (pthread_cond_destroy(&ptr->cond) == 0);
	result &= (pthread_mutex_destroy(&ptr->mutex) == 0);
	
	return result;
}

ShadowBoolean __ShadowSignaler_Wait(ShadowPointer shadowPtr)
{
	CondData* ptr = __extractRawPointer(shadowPtr);

	pthread_mutex_lock(&ptr->mutex);
	int result = pthread_cond_wait(&ptr->cond, &ptr->mutex);
	pthread_mutex_unlock(&ptr->mutex);
	
	return (result == 0);
}

ShadowBoolean __ShadowSignaler_WaitTimeout(ShadowPointer shadowPtr, ShadowLong nanos)
{
	CondData* ptr = __extractRawPointer(shadowPtr);

	struct timespec time = { nanos / NANOS_IN_SECONDS, nanos % NANOS_IN_SECONDS };
	
	pthread_mutex_lock(&ptr->mutex);
	int result = pthread_cond_timedwait_relative_np(&ptr->cond, &ptr->mutex, &time);
	pthread_mutex_unlock(&ptr->mutex);
	
	return (result != 0);
}

ShadowBoolean __ShadowSignaler_Broadcast(ShadowPointer shadowPtr)
{
	CondData* ptr = __extractRawPointer(shadowPtr);
	
	return (pthread_cond_broadcast(&ptr->cond) == 0);
}
