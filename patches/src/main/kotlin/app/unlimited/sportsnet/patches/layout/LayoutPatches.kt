package app.unlimited.sportsnet.patches.layout

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.stringOption
import app.unlimited.sportsnet.patches.shared.Constants.COMPATIBILITY_SPORTSNET
import app.unlimited.sportsnet.patches.shared.originalSignaturePatch
import app.unlimited.sportsnet.patches.shared.Constants.EXTENSION_PACKAGE
import app.unlimited.sportsnet.patches.shared.filterReturnedList
import app.unlimited.sportsnet.patches.shared.setExtensionValue
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

private const val APP_PACKAGE = "Lcom/rogers/sportsnet/"
private const val LISTEN_ICON = "sn_audio_ic"
private const val TRACE_EVENT_START = "traceEventStart"
private const val ICON_BUTTON = "Landroidx/compose/material3/IconButtonKt;"

/** The instructions of a class's methods, with each method's name. */
private fun ClassDef.methodInstructions() = methods.mapNotNull { method ->
    method.implementation?.instructions?.toList()?.let { method.name to it }
}

@Suppress("unused")
val hideListenButtonPatch = bytecodePatch(
    name = "Hide Listen button",
    description = "Removes the Listen (radio and podcasts) button from the top of Home, Scores and Leagues.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SPORTSNET)
    dependsOn(originalSignaturePatch)

    execute {
        val logger = Logger.getLogger(this::class.java.name)

        // The button's icon is a composable lambda kept in a ComposableSingletons class. Compose
        // keys each lambda by the number it passes to traceEventStart, and the getter of the
        // lambda is named after that number: find the numbers of the lambdas drawing the icon.
        val iconGetters = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (!classDef.type.startsWith(APP_PACKAGE) || !classDef.type.contains("ComposableSingletons")) {
                return@classDefForEach
            }
            classDef.methodInstructions().forEach { (_, instructions) ->
                val drawsIcon = instructions.any {
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == LISTEN_ICON
                }
                if (!drawsIcon) return@forEach
                val traceIndex = instructions.indexOfFirst {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == TRACE_EVENT_START
                }
                if (traceIndex < 0) return@forEach
                val keyRegister = (instructions[traceIndex] as FiveRegisterInstruction).registerC
                val key = instructions.subList(0, traceIndex).lastOrNull {
                    it is NarrowLiteralInstruction && (it as OneRegisterInstruction).registerA == keyRegister
                }?.let { (it as NarrowLiteralInstruction).narrowLiteral } ?: return@forEach
                iconGetters += "getLambda\$$key\$"
            }
        }
        if (iconGetters.isEmpty()) throw PatchException("Listen button icon not found")

        // Remove each IconButton whose content is one of those lambdas.
        var removed = 0
        val callers = mutableListOf<Pair<ClassDef, String>>()
        classDefForEach { classDef ->
            if (!classDef.type.startsWith(APP_PACKAGE)) return@classDefForEach
            classDef.methodInstructions().forEach { (name, instructions) ->
                val usesIcon = instructions.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference != null && iconGetters.any { reference.name.startsWith(it) }
                }
                if (usesIcon) callers += classDef to name
            }
        }
        callers.forEach { (classDef, methodName) ->
            mutableClassDefBy(classDef).methods.filter { it.name == methodName }.forEach { method ->
                val instructions = method.instructions
                val iconIndices = instructions.withIndex().filter { (_, instruction) ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference != null && iconGetters.any { reference.name.startsWith(it) }
                }.map { it.index }
                iconIndices.reversed().forEach { iconIndex ->
                    val buttonIndex = (iconIndex until minOf(iconIndex + 20, instructions.size)).firstOrNull {
                        val reference = (instructions[it] as? ReferenceInstruction)?.reference as? MethodReference
                        reference?.definingClass == ICON_BUTTON && reference.name == "IconButton"
                    } ?: return@forEach
                    method.removeInstruction(buttonIndex)
                    removed++
                }
            }
        }
        logger.info("Removed $removed Listen buttons")
        if (removed == 0) throw PatchException("No Listen button found to remove")
    }
}

private const val TABS_CLASS = "$EXTENSION_PACKAGE/BottomTabs;"

@Suppress("unused")
val bottomTabsPatch = bytecodePatch(
    name = "Custom bottom tabs",
    description = "Chooses which tabs the bottom bar shows, their order, and the tab the app opens on.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SPORTSNET)
    dependsOn(originalSignaturePatch)
    extendWith("extensions/extension.mpe")

    val tabs by stringOption(
        key = "tabs",
        default = "home,scores,leagues,watch,more,highlights",
        values = mapOf(
            "Sportsnet's order" to "home,scores,leagues,watch,more,highlights",
            "Watch first" to "watch,scores,home,leagues,more,highlights",
        ),
        title = "Tabs",
        description = "Tabs in order, separated by commas: home, scores, watch, leagues, more, " +
            "highlights. Tabs left out are hidden. Highlights only exists in some versions of the app.",
        required = true,
    ) { value ->
        val names = value?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
        !names.isNullOrEmpty() &&
            names.all { it in setOf("home", "scores", "watch", "leagues", "more", "highlights") }
    }

    val startTab by stringOption(
        key = "startTab",
        default = "",
        title = "Start tab",
        description = "The tab the app opens on, such as watch. Leave empty to open on the first tab.",
        required = false,
    ) { value ->
        value.isNullOrBlank() ||
            value.trim().lowercase() in setOf("home", "scores", "watch", "leagues", "more", "highlights")
    }

    execute {
        setExtensionValue(TABS_CLASS, "tabs", tabs!!)
        setExtensionValue(TABS_CLASS, "startTabName", startTab.orEmpty().trim())

        SportsnetBottomBarFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p0 .. p0}, $TABS_CLASS->visibleTabs(Ljava/util/List;)Ljava/util/List;
                move-result-object p0
            """
        )
        RememberNavigationStateFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p0 .. p0}, $TABS_CLASS->startTab(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p0
                check-cast p0, Landroidx/navigation3/runtime/NavKey;
            """
        )
    }
}

private const val LEAGUES_CLASS = "$EXTENSION_PACKAGE/LeagueFilter;"

@Suppress("unused")
val hideLeaguesPatch = bytecodePatch(
    name = "Hide leagues",
    description = "Keeps the Scores and Watch tabs to the leagues you follow.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SPORTSNET)
    dependsOn(originalSignaturePatch)
    extendWith("extensions/extension.mpe")

    val shownLeagues by stringOption(
        key = "shownLeagues",
        default = "",
        title = "Leagues to show",
        description = "League names as Sportsnet shows them, separated by commas, such as NHL, ATP, WTA. " +
            "Other leagues are hidden; Watch tab videos that name no league stay. Leave empty to show all.",
        required = false,
    )
    val hiddenLeagues by stringOption(
        key = "hiddenLeagues",
        default = "",
        title = "Leagues to hide",
        description = "League names to always hide, separated by commas, such as MLS, WNBA.",
        required = false,
    )

    execute {
        val shown = shownLeagues.orEmpty().trim()
        val hidden = hiddenLeagues.orEmpty().trim()
        if (shown.isEmpty() && hidden.isEmpty()) return@execute
        setExtensionValue(LEAGUES_CLASS, "shownLeagues", shown)
        setExtensionValue(LEAGUES_CLASS, "hiddenLeagues", hidden)

        val filter = "$LEAGUES_CLASS->filter(Ljava/util/List;)Ljava/util/List;"
        listOf(
            leagueScoresFingerprint,
            pageEntriesFingerprint,
            itemListItemsFingerprint,
            sportSchedulesItemsFingerprint,
        ).forEach { filterReturnedList(it.method, filter) }
    }
}

private const val WATCH_SUB_TABS_CLASS = "$EXTENSION_PACKAGE/WatchSubTabs;"

@Suppress("unused")
val watchSubTabPatch = bytecodePatch(
    name = "Watch tab opens on a league",
    description = "Adds a league page as the first Watch sub-tab, so the Watch tab opens on it.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SPORTSNET)
    dependsOn(originalSignaturePatch)
    extendWith("extensions/extension.mpe")

    val subTab by stringOption(
        key = "firstWatchSubTab",
        default = "NHL=/nhl",
        values = mapOf("NHL" to "NHL=/nhl"),
        title = "First Watch sub-tab",
        description = "The sub-tab's label and page path, as Label=/path. " +
            "Nothing changes if the app does not have that page.",
        required = true,
    ) { value -> value != null && Regex("""^[^=]+=/\S*$""").matches(value.trim()) }

    execute {
        setExtensionValue(WATCH_SUB_TABS_CLASS, "firstSubTab", subTab!!.trim())
        WatchSubTabsFactoryFingerprint.method.addInstructions(
            0,
            "invoke-static {p1}, $WATCH_SUB_TABS_CLASS->navigation(Ljava/lang/Object;)V",
        )
        filterReturnedList(
            WatchSubTabsFactoryFingerprint.method,
            "$WATCH_SUB_TABS_CLASS->subTabs(Ljava/util/List;)Ljava/util/List;",
        )
    }
}
