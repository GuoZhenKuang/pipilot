package com.ayagmar.pimobile.coresessions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class SessionIndexRepository(
    private val remoteDataSource: SessionIndexRemoteDataSource,
    private val cache: SessionIndexCache,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val minimumRefreshIntervalMs: Long = 1_000L,
    private val maximumRefreshBackoffMs: Long = 60_000L,
) {
    private val stateByHost = linkedMapOf<String, MutableStateFlow<SessionIndexState>>()
    private val refreshMutexByHost = linkedMapOf<String, Mutex>()
    private val inFlightRefreshes = linkedMapOf<String, CompletableDeferred<SessionIndexState>>()
    private val lastRefreshAttemptByHost = linkedMapOf<String, Long>()
    private val refreshFailuresByHost = linkedMapOf<String, Int>()
    private val crossHostRefreshSemaphore = Semaphore(DEFAULT_MAX_CONCURRENT_REFRESHES)

    suspend fun initialize(hostId: String) {
        loadCache(hostId)
        refreshInBackground(hostId)
    }

    /** Loads every cache before starting network work, then refreshes with a strict host bound. */
    suspend fun initializeAll(
        hostIds: List<String>,
        maxConcurrentRefreshes: Int = DEFAULT_MAX_CONCURRENT_REFRESHES,
    ) {
        require(maxConcurrentRefreshes > 0) { "Refresh concurrency must be positive" }
        hostIds.distinct().forEach { hostId -> loadCache(hostId) }
        refreshAll(hostIds, maxConcurrentRefreshes)
    }

    suspend fun refreshAll(
        hostIds: List<String>,
        maxConcurrentRefreshes: Int = DEFAULT_MAX_CONCURRENT_REFRESHES,
    ) {
        require(maxConcurrentRefreshes > 0) { "Refresh concurrency must be positive" }
        val semaphore = Semaphore(maxConcurrentRefreshes)
        coroutineScope {
            hostIds.distinct().forEach { hostId ->
                launch {
                    semaphore.withPermit { refresh(hostId) }
                }
            }
        }
    }

    fun observe(
        hostId: String,
        query: String = "",
    ): Flow<SessionIndexState> {
        val normalizedQuery = query.trim()
        return stateForHost(hostId).asStateFlow().map { state ->
            state.filter(normalizedQuery)
        }
    }

    fun observeAll(hostIds: List<String>): Flow<List<SessionIndexState>> {
        val distinctHostIds = hostIds.distinct()
        if (distinctHostIds.isEmpty()) return flowOf(emptyList())
        return combine(distinctHostIds.map { hostId -> stateForHost(hostId).asStateFlow() }) { states ->
            states.toList()
        }
    }

    private suspend fun loadCache(hostId: String) {
        val cachedIndex = cache.read(hostId) ?: return
        stateForHost(hostId).value =
            SessionIndexState(
                hostId = hostId,
                groups = cachedIndex.groups,
                isRefreshing = false,
                source = SessionIndexSource.CACHE,
                lastUpdatedEpochMs = cachedIndex.cachedAtEpochMs,
                errorMessage = null,
            )
    }

    suspend fun refresh(hostId: String): SessionIndexState =
        crossHostRefreshSemaphore.withPermit { refreshWithinHostBound(hostId) }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun refreshWithinHostBound(hostId: String): SessionIndexState {
        val state = stateForHost(hostId)
        val existingRefresh = synchronized(inFlightRefreshes) { inFlightRefreshes[hostId] }
        if (existingRefresh != null) return existingRefresh.await()

        val now = nowEpochMs()
        synchronized(lastRefreshAttemptByHost) {
            val lastAttempt = lastRefreshAttemptByHost[hostId]
            val failureCount = synchronized(refreshFailuresByHost) { refreshFailuresByHost[hostId] ?: 0 }
            val backoffMultiplier = 1L shl failureCount.coerceAtMost(MAX_BACKOFF_SHIFT)
            val refreshInterval =
                (minimumRefreshIntervalMs * backoffMultiplier).coerceAtMost(maximumRefreshBackoffMs)
            if (lastAttempt != null && now - lastAttempt < refreshInterval) {
                return state.value
            }
            lastRefreshAttemptByHost[hostId] = now
        }

        val (deferred, owner) =
            synchronized(inFlightRefreshes) {
                val existing = inFlightRefreshes[hostId]
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<SessionIndexState>().also { inFlightRefreshes[hostId] = it } to true
                }
            }
        if (!owner) return deferred.await()

        return try {
            val result = refreshInternal(hostId)
            synchronized(refreshFailuresByHost) {
                if (result.errorMessage == null) {
                    refreshFailuresByHost.remove(hostId)
                } else {
                    refreshFailuresByHost[hostId] = (refreshFailuresByHost[hostId] ?: 0) + 1
                }
            }
            deferred.complete(result)
            result
        } catch (throwable: Throwable) {
            deferred.completeExceptionally(throwable)
            throw throwable
        } finally {
            synchronized(inFlightRefreshes) {
                if (inFlightRefreshes[hostId] === deferred) inFlightRefreshes.remove(hostId)
            }
        }
    }

    private suspend fun refreshInternal(hostId: String): SessionIndexState {
        val mutex = mutexForHost(hostId)
        val state = stateForHost(hostId)

        return mutex.withLock {
            state.update { current -> current.copy(isRefreshing = true, errorMessage = null) }

            runCatching {
                val incomingGroups = remoteDataSource.fetch(hostId)
                val mergedGroups = mergeGroups(existing = state.value.groups, incoming = incomingGroups)
                val updatedState =
                    SessionIndexState(
                        hostId = hostId,
                        groups = mergedGroups,
                        isRefreshing = false,
                        source = SessionIndexSource.REMOTE,
                        lastUpdatedEpochMs = nowEpochMs(),
                        errorMessage = null,
                    )

                cache.write(
                    CachedSessionIndex(
                        hostId = hostId,
                        cachedAtEpochMs = requireNotNull(updatedState.lastUpdatedEpochMs),
                        groups = mergedGroups,
                    ),
                )

                state.value = updatedState
                updatedState
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                val failedState =
                    state.value.copy(
                        isRefreshing = false,
                        errorMessage = throwable.message ?: "Failed to refresh sessions",
                    )
                state.value = failedState
                failedState
            }
        }
    }

    fun refreshInBackground(hostId: String): Job {
        return scope.launch {
            refresh(hostId)
        }
    }

    private fun stateForHost(hostId: String): MutableStateFlow<SessionIndexState> {
        return synchronized(stateByHost) {
            stateByHost.getOrPut(hostId) {
                MutableStateFlow(SessionIndexState(hostId = hostId))
            }
        }
    }

    private fun mutexForHost(hostId: String): Mutex {
        return synchronized(refreshMutexByHost) {
            refreshMutexByHost.getOrPut(hostId) {
                Mutex()
            }
        }
    }
}

private const val MAX_BACKOFF_SHIFT = 6
private const val DEFAULT_MAX_CONCURRENT_REFRESHES = 2

private fun SessionIndexState.filter(query: String): SessionIndexState {
    if (query.isBlank()) return this

    val normalizedQuery = query.lowercase()

    val filteredGroups =
        groups.mapNotNull { group ->
            val filteredSessions = group.sessions.filter { session -> session.matches(normalizedQuery) }
            if (filteredSessions.isEmpty()) null else SessionGroup(cwd = group.cwd, sessions = filteredSessions)
        }

    return copy(groups = filteredGroups)
}

private fun SessionRecord.matches(query: String): Boolean {
    return (displayName?.lowercase()?.contains(query) == true) ||
        (firstUserMessagePreview?.lowercase()?.contains(query) == true) ||
        (lastModel?.lowercase()?.contains(query) == true)
}

private fun mergeGroups(
    existing: List<SessionGroup>,
    incoming: List<SessionGroup>,
): List<SessionGroup> {
    val normalizedIncoming = normalizeStableIdentities(incoming)
    val existingSessions = existing.flatMap { group -> group.sessions }
    val uniqueExistingById =
        existingSessions
            .filter { session -> session.hasStableIdentity }
            .groupBy { session -> requireNotNull(session.sessionId) }
            .filterValues { sessions -> sessions.size == 1 }
            .mapValues { (_, sessions) -> sessions.single() }
    val existingByPath = existingSessions.associateBy { session -> session.sessionPath }
    val existingByCwd = existing.associateBy { group -> group.cwd }

    return normalizedIncoming
        .sortedBy { group -> group.cwd }
        .map { incomingGroup ->
            val mergedSessions =
                incomingGroup.sessions
                    .sortedByDescending { session -> session.updatedAt }
                    .map { incomingSession ->
                        val existingSession =
                            if (incomingSession.hasStableIdentity) {
                                uniqueExistingById[incomingSession.sessionId]
                            } else {
                                existingByPath[incomingSession.sessionPath]
                                    ?.takeIf { cached -> !cached.sessionId.isValidPiSessionId() }
                            }
                        if (existingSession != null && existingSession == incomingSession) {
                            existingSession
                        } else {
                            incomingSession
                        }
                    }

            val existingGroup = existingByCwd[incomingGroup.cwd]
            val unchanged =
                existingGroup != null &&
                    existingGroup.sessions.size == mergedSessions.size &&
                    existingGroup.sessions.zip(mergedSessions).all { (left, right) -> left === right }
            if (unchanged) existingGroup else SessionGroup(incomingGroup.cwd, mergedSessions)
        }
}

private fun normalizeStableIdentities(groups: List<SessionGroup>): List<SessionGroup> {
    val validIdCounts =
        groups
            .flatMap { group -> group.sessions }
            .mapNotNull { session -> session.sessionId?.takeIf { it.isValidPiSessionId() } }
            .groupingBy { it }
            .eachCount()

    return groups.map { group ->
        group.copy(
            sessions =
                group.sessions.map { session ->
                    val sessionId = session.sessionId?.takeIf { it.isValidPiSessionId() }
                    session.copy(
                        sessionId = sessionId,
                        isSessionIdUnique = sessionId != null && validIdCounts[sessionId] == 1,
                    )
                },
        )
    }
}
