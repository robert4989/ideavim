/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2021 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.ex.handler

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.ex.CommandHandler
import com.maddyhome.idea.vim.ex.CommandParser
import com.maddyhome.idea.vim.ex.ExCommand
import com.maddyhome.idea.vim.ex.flags
import com.maddyhome.idea.vim.ex.ranges.LineRange
import com.maddyhome.idea.vim.group.HistoryGroup
import com.maddyhome.idea.vim.group.RegisterGroup
import com.maddyhome.idea.vim.helper.EditorHelper
import com.maddyhome.idea.vim.helper.MessageHelper.message
import com.maddyhome.idea.vim.helper.Msg
import com.maddyhome.idea.vim.helper.shouldIgnoreCase
import com.maddyhome.idea.vim.regexp.CharPointer
import com.maddyhome.idea.vim.regexp.RegExp
import com.maddyhome.idea.vim.regexp.RegExp.regmmatch_T

class GlobalHandler : CommandHandler.SingleExecution() {
  override val argFlags = flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.SELF_SYNCHRONIZED)

  override fun execute(editor: Editor, context: DataContext, cmd: ExCommand): Boolean {
    var result = true
    editor.caretModel.removeSecondaryCarets()
    val caret = editor.caretModel.currentCaret
    val lineRange = cmd.getLineRange(editor, caret)
    if (!processGlobalCommand(editor, context, lineRange, cmd.command, cmd.argument)) {
      result = false
    }
    return result
  }

  private fun processGlobalCommand(
    editor: Editor,
    context: DataContext,
    range: LineRange,
    excmd: String,
    _exarg: String,
  ): Boolean {
    // When nesting the command works on one line.  This allows for
    // ":g/found/v/notfound/command".
    if (globalBusy && (range.startLine != 0 || range.endLine != editor.document.lineCount)) {
      // TODO: 26.05.2021 This is weird
      VimPlugin.showMessage(message("E147"))
      VimPlugin.indicateError()
      return false
    }

    var exarg = _exarg
    val type = if (exarg.startsWith("!")) {
      exarg = exarg.drop(1)
      GlobalType.V
    } else if (excmd.startsWith("g")) {
      GlobalType.G
    } else if (excmd.startsWith("v")) {
      GlobalType.V
    } else error("Unexpected command: $excmd")

    var cmd = CharPointer(StringBuffer(exarg))

    val pat: CharPointer
    val delimiter: Char
    var which_pat = RE_LAST

    /*
     * undocumented vi feature:
     * "\/" and "\?": use previous search pattern.
     *   "\&": use previous substitute pattern.
     */
    if (exarg.isEmpty()) {
      VimPlugin.showMessage(message("E148"))
      VimPlugin.indicateError()
      return false
    } else if (cmd.charAt() == '\\') {
      cmd.inc()
      if ("/?&".indexOf(cmd.charAt()) == -1) {
        VimPlugin.showMessage(message(Msg.e_backslash))
        return false
      }
      which_pat = if (cmd.charAt() == '&') RE_SUBST else RE_SEARCH
      cmd.inc()
      pat = CharPointer("") /* empty search pattern */
    } else {
      delimiter = cmd.charAt() /* get the delimiter */
      cmd.inc() // // TODO: 25.05.2021 Here should be if
      pat = cmd.ref(0) /* remember start of pattern */
      cmd = RegExp.skip_regexp(cmd, delimiter, true)
      if (cmd.charAt() == delimiter) { /* end delimiter found */
        cmd.set('\u0000').inc() /* replace it with a NUL */
      }
    }

    //region search_regcomp implementation
    // We don't need to worry about lastIgnoreSmartCase, it's always false. Vim resets after checking, and it only sets
    // it to true when searching for a word with `*`, `#`, `g*`, etc.
    var isNewPattern = true
    var pattern: String? = ""
    if (pat.isNul) {
      isNewPattern = false
      if (which_pat == RE_LAST) {
        which_pat = lastPatternIdx
      }
      var errorMessage: String? = null
      when (which_pat) {
        RE_SEARCH -> {
          pattern = lastSearch
          errorMessage = message("e_nopresub")
        }
        RE_SUBST -> {
          pattern = lastSubstitute
          errorMessage = message("e_noprevre")
        }
      }

      // Pattern was never defined
      if (pattern == null) {
        VimPlugin.showMessage(errorMessage)
        return false
      }
    } else {
      pattern = pat.toString()
    }

    // Set RE_SUBST and RE_LAST, but only for explicitly typed patterns. Reused patterns are not saved/updated

    setLastUsedPattern(pattern, RE_SUBST, isNewPattern)

    // Always reset after checking, only set for nv_ident
    lastIgnoreSmartCase = false
    // Substitute does NOT reset last direction or pattern offset!

    val regmatch = regmmatch_T()
    regmatch.rmm_ic = shouldIgnoreCase(pattern, false)
    val sp = RegExp()
    regmatch.regprog = sp.vim_regcomp(pattern, 1)
    if (regmatch.regprog == null) {
      if (do_error) {
        VimPlugin.showMessage(message(Msg.e_invcmd))
      }
      return false
    }
    //endregion

    var match: Int
    val lcount = EditorHelper.getLineCount(editor)
    val searchcol = 0
    if (globalBusy) {
      val offset = editor.caretModel.currentCaret.offset
      val lineStartOffset = editor.document.getLineStartOffset(editor.document.getLineNumber(offset))
      match = sp.vim_regexec_multi(regmatch, editor, lcount, lineStartOffset, searchcol)
      if ((type == GlobalType.G && match > 0) || (type == GlobalType.V && match <= 0)) {
        globalExecuteOne(editor, context, lineStartOffset, cmd.toString())
      }
    } else {
      // pass 1: set marks for each (not) matching line
      val line1 = range.startLine
      val line2 = range.endLine
      //region search_regcomp implementation
      // We don't need to worry about lastIgnoreSmartCase, it's always false. Vim resets after checking, and it only sets
      // it to true when searching for a word with `*`, `#`, `g*`, etc.

    if (line1 < 0 || line2 < 0) {
      return false
    }

      var ndone = 0
      val marks = mutableListOf<RangeHighlighter>()
      for (lnum in line1..line2) {
        // TODO: 25.05.2021 recheck gotInt
  //      if (!gotInt) break

        // a match on this line?
        match = sp.vim_regexec_multi(regmatch, editor, lcount, lnum, searchcol)
        if ((type == GlobalType.G && match > 0) || (type == GlobalType.V && match <= 0)) {
          // TODO: 25.05.2021 Use another way to mark things?
          marks += editor.markupModel.addLineHighlighter(null, lnum, 0)
          ndone += 1
        }
        // TODO: 25.05.2021 Check break
      }

      // pass 2: execute the command for each line that has been marked
      /*if (gotInt) {
          // TODO: 25.05.2021
        }
        else */
      if (ndone == 0) {
        if (type == GlobalType.V) {
          VimPlugin.showMessage(message("global.command.not.found.v", pat.toString()))
        } else {
          VimPlugin.showMessage(message("global.command.not.found.g", pat.toString()))
        }
      } else {
        globalExe(editor, context, marks, cmd.toString())
      }
    }
    // TODO: 25.05.2021 More staff
    return true
  }

  private fun globalExe(editor: Editor, context: DataContext, marks: List<RangeHighlighter>, cmd: String) {
    globalBusy = true
    for (mark in marks) {
      if (!globalBusy) break
      val startOffset = mark.startOffset
      globalExecuteOne(editor, context, startOffset, cmd)
      // TODO: 26.05.2021 break check
    }

    globalBusy = false
    // TODO: 26.05.2021 Add other staff
  }

  private fun globalExecuteOne(editor: Editor, context: DataContext, lineStartOffset: Int, cmd: String?) {
    // TODO: 26.05.2021 Move to line start offset
    // TODO: 26.05.2021 What about folds?
    editor.caretModel.moveToOffset(lineStartOffset)
    if (cmd == null || cmd.isEmpty() || (cmd.length == 1 && cmd[0] == '\n')) {
      CommandParser.processCommand(editor, context, "p", 1)
    } else {
      CommandParser.processCommand(editor, context, cmd, 1)
    }
    // TODO: 26.05.2021 Do not add the command to the history
  }

  /**
   * Set the last used pattern
   *
   *
   * Only updates the last used flag if the pattern is new. This prevents incorrectly setting the last used pattern
   * when search or substitute doesn't explicitly set the pattern but uses the last saved value. It also ensures the
   * last used pattern is updated when a new pattern with the same value is used.
   *
   *
   * Also saves the text to the search register and history.
   *
   * @param pattern       The pattern to remember
   * @param which_pat     Which pattern to save - RE_SEARCH, RE_SUBST or RE_BOTH
   * @param isNewPattern  Flag to indicate if the pattern is new, or comes from a last used pattern. True means to
   * update the last used pattern index
   */
  private fun setLastUsedPattern(pattern: String, which_pat: Int, isNewPattern: Boolean) {
    // Only update the last pattern with a new input pattern. Do not update if we're reusing the last pattern
    // TODO: RE_BOTH isn't used in IdeaVim yet. Should be used for the global command
    if ((which_pat == RE_SEARCH || which_pat == RE_BOTH) && isNewPattern) {
      lastSearch = pattern
      lastPatternIdx = RE_SEARCH
    }
    if ((which_pat == RE_SUBST || which_pat == RE_BOTH) && isNewPattern) {
      lastSubstitute = pattern
      lastPatternIdx = RE_SUBST
    }

    // Vim never actually sets this register, but looks it up on request
    VimPlugin.getRegister().storeTextSpecial(RegisterGroup.LAST_SEARCH_REGISTER, pattern)

    // This will remove an existing entry and add it back to the end, and is expected to do so even if the string value
    // is the same
    VimPlugin.getHistory().addEntry(HistoryGroup.SEARCH, pattern)
  }

  private enum class GlobalType {
    G,
    V,
  }

  companion object {
    private var globalBusy = false
    var gotInt: Boolean = false
    private var lastPatternIdx = 0 // Which pattern was used last? RE_SEARCH or RE_SUBST?
    private var lastSearch: String? = null // Pattern used for last search command (`/`)
    private var lastSubstitute: String? = null // Pattern used for last substitute command (`:s`)
    private var lastIgnoreSmartCase = false
    private const val do_error = true /* if false, ignore errors */

    // Matching the values defined in Vim. Do not change these values, they are used as indexes
    private const val RE_SEARCH = 0 // Save/use search pattern
    private const val RE_SUBST = 1 // Save/use substitute pattern
    private const val RE_BOTH = 2 // Save to both patterns
    private const val RE_LAST = 2 // Use last used pattern if "pat" is NULL
  }
}
