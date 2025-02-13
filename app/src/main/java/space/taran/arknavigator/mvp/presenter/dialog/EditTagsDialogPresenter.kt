package space.taran.arknavigator.mvp.presenter.dialog

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import moxy.MvpPresenter
import moxy.presenterScope
import space.taran.arkfilepicker.folders.RootAndFav
import space.taran.arklib.ResourceId
import space.taran.arklib.domain.index.ResourcesIndex
import space.taran.arklib.domain.index.ResourcesIndexRepo
import space.taran.arknavigator.mvp.model.repo.preferences.PreferenceKey
import space.taran.arknavigator.mvp.model.repo.preferences.Preferences
import space.taran.arknavigator.mvp.model.repo.stats.StatsStorage
import space.taran.arknavigator.mvp.model.repo.stats.StatsStorageRepo
import space.taran.arknavigator.mvp.model.repo.tags.TagsStorage
import space.taran.arknavigator.mvp.model.repo.tags.TagsStorageRepo
import space.taran.arknavigator.mvp.view.dialog.EditTagsDialogView
import space.taran.arknavigator.utils.Popularity
import space.taran.arknavigator.utils.Tag
import space.taran.arknavigator.utils.TagUtils
import javax.inject.Inject

sealed class EditTagsAction {
    data class AddTag(
        val tag: Tag,
        val affectedIds: Set<ResourceId>
    ) : EditTagsAction()

    data class RemoveTag(val tag: Tag) : EditTagsAction()
}

class EditTagsDialogPresenter(
    private val rootAndFav: RootAndFav,
    val resources: List<ResourceId>,
    private val _index: ResourcesIndex?,
    private val _storage: TagsStorage?,
    private val _statsStorage: StatsStorage?
) : MvpPresenter<EditTagsDialogView>() {

    private lateinit var index: ResourcesIndex
    private lateinit var storage: TagsStorage
    private lateinit var statsStorage: StatsStorage

    @Inject
    lateinit var indexRepo: ResourcesIndexRepo

    @Inject
    lateinit var tagsStorageRepo: TagsStorageRepo

    @Inject
    lateinit var statsStorageRepo: StatsStorageRepo

    @Inject
    lateinit var preferences: Preferences

    private var sorting = TagsSorting.POPULARITY
    private var sortingAscending = false

    private var input = ""
        set(value) {
            field = value
            viewState.setInput(field)
        }
    private val actionsHistory = ArrayDeque<EditTagsAction>()
    private val tagsByResources = mutableMapOf<ResourceId, MutableSet<Tag>>()
    private val commonTags = mutableSetOf<Tag>()
    private var quickTags = listOf<Tag>()

    private var wasTextRemovedRecently = false
    private var wasTagRemovedRecently = false
    private var textRemovedRecentlyJob: Job? = null

    override fun onFirstViewAttach() {
        viewState.init()
        presenterScope.launch {
            val showTagSorting = preferences.get(PreferenceKey.CollectTagUsageStats)
            if (showTagSorting)
                initSortingPref()
            else
                viewState.hideSortingBtn()

            if (_index != null && _storage != null && _statsStorage != null) {
                index = _index
                storage = _storage
                statsStorage = _statsStorage
                init()
            } else {
                index = indexRepo.provide(rootAndFav)
                storage = tagsStorageRepo.provide(rootAndFav)
                statsStorage = statsStorageRepo.provide(rootAndFav)
                init()
            }
        }
    }

    private suspend fun init() {
        tagsByResources += resources
            .associateWith { id -> storage.getTags(id).toMutableSet() }
            .toMutableMap()
        commonTags += listCommonTags()
        quickTags = listQuickTags()
        viewState.setQuickTags(filterQuickTags())
        viewState.setResourceTags(commonTags)
        viewState.showKeyboardAndView()
    }

    private suspend fun initSortingPref() {
        sorting = TagsSorting.values()[
            preferences.get(PreferenceKey.TagsSortingEdit)
        ]
        sortingAscending = preferences.get(PreferenceKey.TagsSortingEditAsc)
        preferences.flow(PreferenceKey.TagsSortingEdit).onEach {
            val newSorting = TagsSorting.values()[it]
            if (sorting == newSorting) return@onEach
            sorting = newSorting
            quickTags = listQuickTags()
            viewState.setQuickTags(filterQuickTags())
        }.launchIn(presenterScope)
        preferences.flow(PreferenceKey.TagsSortingEditAsc).onEach { ascending ->
            if (sortingAscending == ascending) return@onEach
            sortingAscending = ascending
            quickTags = listQuickTags()
            viewState.setQuickTags(filterQuickTags())
        }.launchIn(presenterScope)
    }

    fun onInputChanged(newInput: String) {
        if (input.length > newInput.length) {
            wasTextRemovedRecently = true
            textRemovedRecentlyTimer()
        }

        if (TAG_SEPARATORS.any { newInput.endsWith(it) }) {
            if (newInput.length > 1) {
                val tag = newInput.substring(0, newInput.lastIndex)
                addTag(tag)
            }
            input = ""
            return
        }

        input = newInput
        viewState.setQuickTags(filterQuickTags())
    }

    fun onAddBtnClick() {
        if (input.isEmpty()) return
        addTag(input)
        input = ""
    }

    fun onResourceTagClick(tag: Tag) = removeTag(tag)

    fun onQuickTagClick(tag: Tag) {
        input = ""
        addTag(tag)
    }

    fun onBackspacePressed() {
        if (input.isNotEmpty() || wasTextRemovedRecently || wasTagRemovedRecently)
            return

        val lastTag = commonTags.lastOrNull() ?: return
        removeTag(lastTag)
        wasTagRemovedRecently = true
        tagWasRemovedRecentlyTimer()
    }

    fun onBackClick(): Boolean {
        if (actionsHistory.isEmpty()) return false

        when (val lastAction = actionsHistory.last()) {
            is EditTagsAction.AddTag -> {
                commonTags -= lastAction.tag
                lastAction.affectedIds.forEach { id ->
                    tagsByResources[id]?.minusAssign(lastAction.tag)
                }
            }
            is EditTagsAction.RemoveTag -> {
                commonTags += lastAction.tag
                tagsByResources.forEach { entry ->
                    entry.value += lastAction.tag
                }
            }
        }
        actionsHistory.removeLast()
        updateTags()

        return true
    }

    fun onInputDone() = presenterScope.launch {
        if (input.isNotEmpty())
            addTag(input)

        tagsByResources.forEach { entry ->
            storage.setTags(entry.key, entry.value)
        }
        launch { storage.persist() }
        viewState.dismissDialog()
    }

    private fun addTag(tag: Tag) {
        val validatedTag = TagUtils.validateTag(tag) ?: return

        commonTags += validatedTag
        val affectedIds = tagsByResources.mapNotNull { entry ->
            if (entry.value.contains(validatedTag))
                return@mapNotNull null

            entry.value += validatedTag
            entry.key
        }.toSet()

        actionsHistory.addLast(EditTagsAction.AddTag(validatedTag, affectedIds))
        updateTags()
    }

    private fun removeTag(tag: Tag) {
        commonTags -= tag
        tagsByResources.forEach { entry ->
            entry.value -= tag
        }

        actionsHistory.addLast(EditTagsAction.RemoveTag(tag))
        updateTags()
    }

    private fun updateTags() {
        viewState.setResourceTags(commonTags)
        viewState.setQuickTags(filterQuickTags())
    }

    private fun textRemovedRecentlyTimer() {
        textRemovedRecentlyJob?.cancel()
        textRemovedRecentlyJob = presenterScope.launch {
            delay(BACKSPACE_GAP_BETWEEN_TEXT_AND_TAG)
            ensureActive()
            wasTextRemovedRecently = false
        }
    }

    private fun tagWasRemovedRecentlyTimer() = presenterScope.launch {
        delay(BACKSPACE_GAP_BETWEEN_TAGS)
        wasTagRemovedRecently = false
    }

    private fun filterQuickTags(): List<Tag> =
        (quickTags - commonTags)
            .filter { tag ->
                tag.startsWith(input, true)
            }

    private suspend fun listQuickTags(): List<Tag> {
        val allTags = storage.groupTagsByResources(index.listAllIds())
            .values
            .flatten()
        val sortCriteria = when (sorting) {
            TagsSorting.POPULARITY -> Popularity.calculate(allTags)
            TagsSorting.QUERIED_TS -> statsStorage.statsTagQueriedTS()
            TagsSorting.QUERIED_N -> statsStorage.statsTagQueriedAmount()
            TagsSorting.LABELED_TS -> statsStorage.statsTagLabeledTS()
            TagsSorting.LABELED_N -> statsStorage.statsTagLabeledAmount()
        } as Map<Tag, Comparable<Any>>

        var quick = allTags
            .distinct()
            .sortedBy { sortCriteria[it] }

        if (!sortingAscending)
            quick = quick.reversed()

        return quick
    }

    private fun listCommonTags(): MutableSet<Tag> {
        val tagsList = resources.map { id -> storage.getTags(id) }
        var common = tagsList.first()
        tagsList.drop(1).forEach { tags ->
            common = common.intersect(tags)
        }
        return common.toMutableSet()
    }

    companion object {
        private val TAG_SEPARATORS = listOf(",")
        private const val BACKSPACE_GAP_BETWEEN_TEXT_AND_TAG = 1000L // ms
        private const val BACKSPACE_GAP_BETWEEN_TAGS = 500L // ms
    }
}
