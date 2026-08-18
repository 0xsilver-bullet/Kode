package com.silverbullet.kode.feature.threads.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The server issues asset URLs relative to the environment's HTTP base, and
 * paired bases come from user input, so trailing slashes are not guaranteed
 * either way.
 */
class AssetUrlResolutionTest {

    @Test
    fun `joins a root relative path onto a bare base`() {
        assertEquals(
            "http://192.168.1.4:3000/api/assets/tok/a.png",
            resolveAgainstBase("http://192.168.1.4:3000", "/api/assets/tok/a.png"),
        )
    }

    @Test
    fun `does not double the separator when the base ends in a slash`() {
        assertEquals(
            "https://host.example/api/assets/tok/a.png",
            resolveAgainstBase("https://host.example/", "/api/assets/tok/a.png"),
        )
    }

    @Test
    fun `joins a path that does not start with a slash`() {
        assertEquals(
            "https://host.example/api/assets/tok/a.png",
            resolveAgainstBase("https://host.example", "api/assets/tok/a.png"),
        )
    }

    @Test
    fun `passes an already absolute url through untouched`() {
        assertEquals(
            "https://cdn.example/a.png",
            resolveAgainstBase("https://host.example", "https://cdn.example/a.png"),
        )
    }

    @Test
    fun `refuses to build a url from an empty base or path`() {
        assertNull(resolveAgainstBase("https://host.example", ""))
        assertNull(resolveAgainstBase("", "/api/assets/tok/a.png"))
        assertNull(resolveAgainstBase("/", "/api/assets/tok/a.png"))
    }
}
