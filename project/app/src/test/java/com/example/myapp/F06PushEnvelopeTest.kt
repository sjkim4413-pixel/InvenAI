package com.example.myapp

import org.junit.Assert.*
import org.junit.Test

class F06PushEnvelopeTest {
    private val payload = mapOf("type" to "invenai_anomaly", "kind" to "audit", "alert_id" to "AI_${"a".repeat(64)}",
        "company_code" to "ABC-002", "recipient_uid" to "loader", "event_id" to "b".repeat(64))

    @Test fun onlyRoutingIdentifiersAreNeededForAnAuditPush() {
        val parsed = requireNotNull(parseF06Push(payload))
        assertEquals("ABC-002", parsed.company)
        assertEquals("loader", parsed.uid)
        assertEquals("audit", parsed.kind)
    }

    @Test fun inventoryNotificationsHaveAnExplicitSeparateKind() {
        val parsed = requireNotNull(parseF06Push(payload + mapOf("kind" to "inventory", "alert_id" to "ABC-002_ITEM-1")))
        assertEquals("inventory", parsed.kind)
    }

    @Test fun unknownTypesAndDocumentPathsAreRejected() {
        for (changes in listOf(mapOf("type" to "other"), mapOf("kind" to "membership"), mapOf("alert_id" to "../other"),
                mapOf("alert_id" to "arbitrary"),mapOf("recipient_uid" to "other/member"))) {
            assertNull(parseF06Push(payload + changes))
        }
    }

    @Test fun missingRecipientAndCompanyCannotBecomeABroadcast() {
        for (key in listOf("recipient_uid", "company_code", "event_id")) assertNull(parseF06Push(payload - key))
        assertNull(parseF06Push(payload + ("company_code" to "company/other")))
    }
}
