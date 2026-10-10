package dev.tokito.app.ffi

import org.junit.Assert.*
import org.junit.Test

class AuthorizationEpochTest {
  @Test fun retiredAccountCannotOwnNewAccount() {
    val epochs = AuthorizationEpoch()
    val old = epochs.begin("https://api.example", "old")
    val current = epochs.begin("https://api.example", "new")
    assertFalse(epochs.owns(old))
    assertTrue(epochs.owns(current))
    assertNull(epochs.matching("https://api.example", "old"))
  }

  @Test fun sameCredentialsReopenedAreStillANewLifetime() {
    val epochs = AuthorizationEpoch()
    val old = epochs.begin("https://api.example", "token")
    epochs.invalidate()
    val current = epochs.begin("https://api.example", "token")
    assertFalse(epochs.owns(old))
    assertTrue(epochs.owns(current))
  }

  @Test fun stoppingRejectsAllRetiredCallbacks() {
    val epochs = AuthorizationEpoch()
    val old = epochs.begin("https://api.example", "token")
    epochs.invalidate()
    assertFalse(epochs.owns(old))
    assertFalse(epochs.owns(null))
    assertNull(epochs.capture())
  }

  @Test fun originAndCredentialMustBothMatch() {
    val epochs = AuthorizationEpoch()
    val lease = epochs.begin("https://api.example", "token")
    assertSame(lease, epochs.matching("https://api.example", "token"))
    assertNull(epochs.matching("https://other.example", "token"))
    assertNull(epochs.matching("https://api.example", "other"))
  }
}
