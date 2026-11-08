// SPDX-License-Identifier: Apache-2.0
package app.mixu.graph

import kotlin.test.Test
import kotlin.test.assertEquals

class GraphBootstrapTest {
    @Test
    fun moduleNameMatches() {
        assertEquals("mixu-graph", GraphBootstrap.MODULE_NAME)
    }
}
