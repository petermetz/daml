// Copyright (c) 2019 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.auth

trait PublicServiceCallAuthTests extends ServiceCallAuthTests {

  it should "deny calls with an expired read-only token" in {
    expectPermissionDenied(serviceCallWithToken(canReadAsRandomPartyExpired))
  }
  it should "allow calls with explicitly non-expired read-only token" in {
    expectSuccess(serviceCallWithToken(canReadAsRandomPartyExpiresTomorrow))
  }
  it should "allow calls with read-only token without expiration" in {
    expectSuccess(serviceCallWithToken(canReadAsRandomParty))
  }

  it should "deny calls with an expired read/write token" in {
    expectPermissionDenied(serviceCallWithToken(canActAsRandomPartyExpired))
  }
  it should "allow calls with explicitly non-expired read/write token" in {
    expectSuccess(serviceCallWithToken(canActAsRandomPartyExpiresTomorrow))
  }
  it should "allow calls with read/write token without expiration" in {
    expectSuccess(serviceCallWithToken(canActAsRandomParty))
  }

  it should "deny calls with an expired admin token" in {
    expectPermissionDenied(serviceCallWithToken(canReadAsAdminExpired))
  }
  it should "allow calls with explicitly non-expired admin token" in {
    expectSuccess(serviceCallWithToken(canReadAsAdminExpiresTomorrow))
  }
  it should "allow calls with admin token without expiration" in {
    expectSuccess(serviceCallWithToken(canReadAsAdmin))
  }

}
