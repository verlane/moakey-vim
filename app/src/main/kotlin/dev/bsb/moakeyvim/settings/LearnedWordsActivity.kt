package dev.bsb.moakeyvim.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koin.android.ext.android.get
import org.koin.core.qualifier.named
import dev.bsb.moakeyvim.R
import dev.bsb.moakeyvim.databinding.ActivityLearnedWordsBinding
import dev.bsb.moakeyvim.settings.learnedwords.LearnedWordItem
import dev.bsb.moakeyvim.settings.learnedwords.LearnedWordsSortOrder
import dev.bsb.moakeyvim.settings.learnedwords.LearnedWordsAdapter
import dev.bsb.moakeyvim.settings.learnedwords.filterByQuery
import dev.bsb.moakeyvim.settings.learnedwords.sortedByOrder
import dev.bsb.moakeyvim.suggestion.UserWordStore
import dev.bsb.moakeyvim.suggestion.WordTokenizer

class LearnedWordsActivity : AppCompatActivity() {

    private enum class Tab { KO, EN, BLACKLIST }

    private companion object {
        const val PAGE_SIZE = 50
        const val SEARCH_DEBOUNCE_MS = 200L
        const val KEY_TAB = "tab"
        const val KEY_SEARCH = "search"
        const val KEY_VISIBLE_SIZE = "visible_size"
    }

    private lateinit var binding: ActivityLearnedWordsBinding
    private lateinit var koStore: UserWordStore
    private lateinit var enStore: UserWordStore
    private lateinit var adapter: LearnedWordsAdapter

    private var currentTab = Tab.KO
    private var sortOrder = LearnedWordsSortOrder.DEFAULT
    private var masterList: List<LearnedWordItem> = emptyList()
    private var filteredList: List<LearnedWordItem> = emptyList()
    private var visibleSize = PAGE_SIZE

    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { applyFilter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLearnedWordsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        koStore = get(named("ko"))
        enStore = get(named("en"))
        sortOrder = SettingsPreferences.getLearnedWordsSortOrder(this)

        setupAdapter()
        setupRecyclerView()
        setupSearchInput()
        binding.addButton.setOnClickListener { showAddWordDialog() }

        if (savedInstanceState != null) {
            currentTab = Tab.valueOf(savedInstanceState.getString(KEY_TAB, Tab.KO.name))
            binding.searchInput.setText(savedInstanceState.getString(KEY_SEARCH, ""))
            visibleSize = savedInstanceState.getInt(KEY_VISIBLE_SIZE, PAGE_SIZE)
        }
        setupTabButtons()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab.name)
        outState.putString(KEY_SEARCH, binding.searchInput.text?.toString() ?: "")
        outState.putInt(KEY_VISIBLE_SIZE, visibleSize)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_learned_words, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isWordTab = currentTab != Tab.BLACKLIST
        menu.findItem(R.id.menu_delete_all)?.isVisible = isWordTab
        menu.findItem(R.id.menu_prune_30)?.isVisible = isWordTab
        menu.findItem(R.id.menu_sort)?.isVisible = isWordTab
        applySortCheck(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_delete_all -> { showDeleteAllConfirm(); return true }
            R.id.menu_prune_30 -> { showPruneConfirm(); return true }
            R.id.menu_sort_count_desc -> { setSortOrder(LearnedWordsSortOrder.COUNT_DESC); return true }
            R.id.menu_sort_count_asc -> { setSortOrder(LearnedWordsSortOrder.COUNT_ASC); return true }
            R.id.menu_sort_word_asc -> { setSortOrder(LearnedWordsSortOrder.WORD_ASC); return true }
            R.id.menu_sort_word_desc -> { setSortOrder(LearnedWordsSortOrder.WORD_DESC); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        searchHandler.removeCallbacks(searchRunnable)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun applySortCheck(menu: Menu) {
        val checked = when (sortOrder) {
            LearnedWordsSortOrder.COUNT_DESC -> R.id.menu_sort_count_desc
            LearnedWordsSortOrder.COUNT_ASC -> R.id.menu_sort_count_asc
            LearnedWordsSortOrder.WORD_ASC -> R.id.menu_sort_word_asc
            LearnedWordsSortOrder.WORD_DESC -> R.id.menu_sort_word_desc
        }
        menu.findItem(checked)?.isChecked = true
    }

    private fun setSortOrder(order: LearnedWordsSortOrder) {
        sortOrder = order
        SettingsPreferences.setLearnedWordsSortOrder(this, order)
        visibleSize = PAGE_SIZE
        refreshMasterList()
        invalidateOptionsMenu()
    }

    private fun setupAdapter() {
        adapter = LearnedWordsAdapter(
            onWordClick = { item -> showActionDialog(item.word) },
            onWordDelete = { item -> showDeleteConfirm(item.word) },
            onBlacklistRelease = { item -> showBlacklistReleaseConfirm(item.word, item.isEn) },
        )
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(this)
        binding.wordRecyclerView.layoutManager = layoutManager
        binding.wordRecyclerView.adapter = adapter
        binding.wordRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= adapter.currentList.size - 10 && visibleSize < filteredList.size) {
                    visibleSize += PAGE_SIZE
                    submitVisible()
                }
            }
        })
    }

    private fun setupSearchInput() {
        binding.searchInput.addTextChangedListener {
            searchHandler.removeCallbacks(searchRunnable)
            searchHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
        }
    }

    private fun setupTabButtons() {
        if (!SettingsPreferences.getEnglishKeyboardEnabled(this)) {
            binding.tabEnButton.visibility = android.view.View.GONE
            if (currentTab == Tab.EN) currentTab = Tab.KO
        }
        val checkId = when (currentTab) {
            Tab.EN -> R.id.tabEnButton
            Tab.BLACKLIST -> R.id.tabBlacklistButton
            Tab.KO -> R.id.tabKoButton
        }
        binding.tabToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            switchTab(when (checkedId) {
                R.id.tabEnButton -> Tab.EN
                R.id.tabBlacklistButton -> Tab.BLACKLIST
                else -> Tab.KO
            })
        }
        binding.tabToggleGroup.check(checkId)
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        binding.addButton.visibility = if (tab == Tab.BLACKLIST) View.GONE else View.VISIBLE
        binding.searchLayout.visibility = View.VISIBLE
        visibleSize = PAGE_SIZE
        invalidateOptionsMenu()
        refreshMasterList()
    }

    private fun refreshMasterList() {
        masterList = when (currentTab) {
            Tab.KO -> buildWordItems(koStore)
            Tab.EN -> buildWordItems(enStore)
            Tab.BLACKLIST -> buildBlacklistItems()
        }
        applyFilter()
    }

    private fun buildWordItems(store: UserWordStore): List<LearnedWordItem> =
        store.entries()
            .sortedByOrder(sortOrder)
            .map { (word, count) -> LearnedWordItem.Word(word, count) }

    private fun buildBlacklistItems(): List<LearnedWordItem> {
        val ko = koStore.blacklist().sorted().map { LearnedWordItem.Blacklist(it, false) }
        val en = enStore.blacklist().sorted().map { LearnedWordItem.Blacklist(it, true) }
        return ko + en
    }

    private fun applyFilter() {
        val query = binding.searchInput.text?.toString() ?: ""
        filteredList = masterList.filterByQuery(query)
        submitVisible()
    }

    private fun submitVisible() {
        val filtered = filteredList
        val visible = filtered.take(visibleSize)
        adapter.submitList(visible)

        val isEmpty = filtered.isEmpty()
        binding.wordRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.emptyText.text = when {
            masterList.isEmpty() -> getString(
                if (currentTab == Tab.BLACKLIST) R.string.settings_learned_words_blacklist_empty
                else R.string.settings_learned_words_empty
            )
            else -> getString(R.string.settings_learned_words_search_empty)
        }

        if (isEmpty || currentTab == Tab.BLACKLIST) {
            binding.countText.visibility = View.GONE
        } else {
            binding.countText.visibility = View.VISIBLE
            binding.countText.text = if (filtered.size == masterList.size) {
                getString(R.string.settings_learned_words_count_all, masterList.size)
            } else {
                getString(R.string.settings_learned_words_count, masterList.size, filtered.size)
            }
        }
    }

    private fun showActionDialog(word: String) {
        val store = when (currentTab) {
            Tab.KO -> koStore
            Tab.EN -> enStore
            Tab.BLACKLIST -> return
        }
        val items = arrayOf(
            getString(R.string.suggestion_long_click_remove),
            getString(R.string.suggestion_long_click_blacklist),
        )
        AlertDialog.Builder(this)
            .setTitle(word)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { store.remove(word); refreshMasterList() }
                    1 -> { store.addToBlacklist(word); refreshMasterList() }
                }
            }
            .show()
    }

    private fun showDeleteConfirm(word: String) {
        val store = when (currentTab) {
            Tab.KO -> koStore
            Tab.EN -> enStore
            Tab.BLACKLIST -> return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.settings_learned_words_delete_confirm, word))
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                store.remove(word)
                refreshMasterList()
            }
            .setNegativeButton(R.string.settings_qwerty_long_key_cancel, null)
            .show()
    }

    private fun showDeleteAllConfirm() {
        val store = when (currentTab) {
            Tab.KO -> koStore
            Tab.EN -> enStore
            Tab.BLACKLIST -> return
        }
        val count = store.entries().size
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.settings_learned_words_delete_all_confirm) + " ($count)")
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                store.clear()
                refreshMasterList()
            }
            .setNegativeButton(R.string.settings_qwerty_long_key_cancel, null)
            .show()
    }

    private fun showAddWordDialog() {
        val isKo = currentTab == Tab.KO
        val store = if (isKo) koStore else enStore
        val hint = getString(
            if (isKo) R.string.settings_learned_words_add_hint_ko
            else R.string.settings_learned_words_add_hint_en
        )
        val dp16 = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            this.hint = hint
            setSingleLine()
            setPadding(dp16, dp16, dp16, dp16)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_learned_words_add))
            .setView(input)
            .setPositiveButton(R.string.dialog_confirm, null)
            .setNegativeButton(R.string.settings_qwerty_long_key_cancel, null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString()
                val word = if (isKo) WordTokenizer.extractKorean(raw) else WordTokenizer.extractEnglish(raw)
                when {
                    word == null -> input.error = getString(
                        if (isKo) R.string.settings_learned_words_add_invalid_ko
                        else R.string.settings_learned_words_add_invalid_en
                    )
                    store.contains(word) -> input.error = getString(R.string.settings_learned_words_add_duplicate)
                    else -> {
                        val minCount = SettingsPreferences.getMinLearnCount(this@LearnedWordsActivity)
                        store.importWords(mapOf(word to minCount))
                        dialog.dismiss()
                        refreshMasterList()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showPruneConfirm() {
        val store = when (currentTab) {
            Tab.KO -> koStore
            Tab.EN -> enStore
            Tab.BLACKLIST -> return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.settings_learned_words_prune_confirm)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val removed = store.pruneOlderThan(30)
                val msg = if (removed > 0) {
                    getString(R.string.settings_learned_words_prune_result, removed)
                } else {
                    getString(R.string.settings_learned_words_prune_none)
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                if (removed > 0) refreshMasterList()
            }
            .setNegativeButton(R.string.settings_qwerty_long_key_cancel, null)
            .show()
    }

    private fun showBlacklistReleaseConfirm(word: String, isEn: Boolean) {
        val store = if (isEn) enStore else koStore
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.settings_learned_words_blacklist_release_confirm, word))
            .setPositiveButton(R.string.settings_learned_words_blacklist_release) { _, _ ->
                store.removeFromBlacklist(word)
                refreshMasterList()
            }
            .setNegativeButton(R.string.settings_qwerty_long_key_cancel, null)
            .show()
    }
}
