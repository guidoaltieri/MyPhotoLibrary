package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.guidoaltieri.myphotolibrary.data.Repo

/** Schermate dell'app (pila di navigazione semplice, il tasto Indietro toglie l'ultima). */
sealed interface Screen {
    data object Home : Screen
    data class Grid(val view: String, val title: String, val query: String = "") : Screen
    data class Ids(val ids: List<Long>, val title: String, val subtitle: String = "") : Screen
    data class Viewer(val ids: List<Long>, val index: Int, val view: String = "") : Screen
    data class Editor(val id: Long) : Screen
    data class Memory(val key: String) : Screen
    data class Slideshow(val ids: List<Long>, val title: String) : Screen
    data class Albums(val parent: Long?, val title: String) : Screen
    data class Album(val id: Long) : Screen
    data class SmartEditor(val albumId: Long?, val initialQuery: String = "") : Screen
    data object People : Screen
    data class Person(val id: Long) : Screen
    data object Places : Screen
    data object Duplicates : Screen
    data object Imports : Screen
    data object Suggestions : Screen
    data object Sources : Screen
    data object Settings : Screen
    data object Import : Screen
    data object ICloud : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val top: Screen get() = stack.last()
    fun go(s: Screen) {
        stack.add(s)
    }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
    fun replace(s: Screen) {
        stack[stack.lastIndex] = s
    }
}

val LocalRepo = staticCompositionLocalOf<Repo> { error("Repo non disponibile") }
val LocalNav = staticCompositionLocalOf<Nav> { error("Nav non disponibile") }

@Composable
fun repo() = LocalRepo.current

@Composable
fun nav() = LocalNav.current
