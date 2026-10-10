package dev.tokito.app.ffi

import org.junit.Assert.*
import org.junit.Test

class AuthorizationEpochTest {
  @Test fun delayedStartupCannotAcquireNewWorkspaceEvenWithIdenticalCredentials() {
    val epochs = AuthorizationEpoch()
    val old = epochs.begin("https://api.example", "token", "old-owner")
    val release = java.util.concurrent.CountDownLatch(1)
    val finished = java.util.concurrent.CountDownLatch(1)
    var rejected = false
    val delayed = Thread {
      release.await()
      try { epochs.requireOwner(old.base, old.token, old.owner) }
      catch (_: IllegalStateException) { rejected = true }
      finally { finished.countDown() }
    }
    delayed.start()
    val current = epochs.begin(old.base, old.token, "new-owner")
    release.countDown()
    assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
    assertTrue(rejected)
    assertSame(current, epochs.capture())
    assertFalse(epochs.owns(old))
  }

  @Test fun startupCompletionCannotInstallAfterAccountSwitch() {
    val epochs = AuthorizationEpoch()
    val started = epochs.begin("https://api.example", "old", "first")
    assertSame(started, epochs.requireOwner(started.base, started.token, started.owner))
    val current = epochs.begin("https://api.example", "new", "second")
    assertFalse(epochs.owns(started))
    assertTrue(epochs.owns(current))
    assertThrows(IllegalStateException::class.java) {
      epochs.requireOwner(started.base, started.token, started.owner)
    }
    assertSame(current, epochs.capture())
  }

  @Test fun retiredAuthorizationFailureCannotRevokeCurrentWorkspace() {
    val epochs = AuthorizationEpoch()
    val retired = epochs.begin("https://api.example", "old", "first")
    val current = epochs.begin("https://api.example", "new", "second")
    assertFalse(epochs.invalidateIfOwned(retired))
    assertTrue(epochs.owns(current))
    assertTrue(epochs.invalidateIfOwned(current))
    assertNull(epochs.capture())
  }

  @Test fun delayedWorkerCompletionIsNotInstalledIntoNewAccount() {
    val epochs = AuthorizationEpoch()
    val old = epochs.begin("https://api.example", "old", "first")
    val releaseIo = java.util.concurrent.CountDownLatch(1)
    val completed = java.util.concurrent.CountDownLatch(1)
    var installed = false
    Thread {
      releaseIo.await()
      installed = epochs.owns(old)
      completed.countDown()
    }.start()
    val current = epochs.begin("https://api.example", "new", "second")
    releaseIo.countDown()
    assertTrue(completed.await(2, java.util.concurrent.TimeUnit.SECONDS))
    assertFalse(installed)
    assertSame(current, epochs.capture())
  }
  @Test fun retiredAccountCannotOwnNewAccount() {
    val epochs = AuthorizationEpoch()
    val old = epochs.begin("https://api.example", "old", "first")
    val current = epochs.begin("https://api.example", "new", "second")
    assertFalse(epochs.owns(old))
    assertTrue(epochs.owns(current))
    assertNull(epochs.matching("https://api.example", "old"))
  }

  @Test fun sameCredentialsReopenedAreStillANewLifetime() {
    val epochs = AuthorizationEpoch()
    val old = epochs.begin("https://api.example", "token", "first")
    epochs.invalidate()
    val current = epochs.begin("https://api.example", "token", "second")
    assertFalse(epochs.owns(old))
    assertTrue(epochs.owns(current))
  }

  @Test fun stoppingRejectsAllRetiredCallbacks() {
    val epochs = AuthorizationEpoch()
    val old = epochs.begin("https://api.example", "token", "first")
    epochs.invalidate()
    assertFalse(epochs.owns(old))
    assertFalse(epochs.owns(null))
    assertNull(epochs.capture())
  }

  @Test fun originAndCredentialMustBothMatch() {
    val epochs = AuthorizationEpoch()
    val lease = epochs.begin("https://api.example", "token", "first")
    assertSame(lease, epochs.matching("https://api.example", "token"))
    assertNull(epochs.matching("https://other.example", "token"))
    assertNull(epochs.matching("https://api.example", "other"))
  }
}
