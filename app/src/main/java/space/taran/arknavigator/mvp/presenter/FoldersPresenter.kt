package space.taran.arknavigator.mvp.presenter

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import moxy.MvpPresenter
import moxy.presenterScope
import space.taran.arkfilepicker.folders.FoldersRepo
import space.taran.arkfilepicker.folders.RootAndFav
import space.taran.arkfilepicker.presentation.folderstree.DeviceNode
import space.taran.arkfilepicker.presentation.folderstree.FavoriteNode
import space.taran.arkfilepicker.presentation.folderstree.FolderNode
import space.taran.arkfilepicker.presentation.folderstree.RootNode
import space.taran.arklib.domain.Message
import space.taran.arklib.domain.index.ResourcesIndexRepo
import space.taran.arklib.domain.preview.PreviewStorageRepo
import space.taran.arklib.utils.Constants
import space.taran.arknavigator.mvp.model.repo.preferences.PreferenceKey
import space.taran.arknavigator.mvp.model.repo.preferences.Preferences
import space.taran.arknavigator.mvp.view.FoldersView
import space.taran.arknavigator.navigation.AppRouter
import space.taran.arknavigator.navigation.Screens
import space.taran.arknavigator.ui.resource.StringProvider
import space.taran.arknavigator.utils.LogTags.FOLDERS_SCREEN
import space.taran.arknavigator.utils.LogTags.FOLDERS_TREE
import space.taran.arknavigator.utils.listDevices
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Named

class FoldersPresenter(
    private val rescanRoots: Boolean
) : MvpPresenter<FoldersView>() {
    @Inject
    lateinit var router: AppRouter

    @Inject
    lateinit var foldersRepo: FoldersRepo

    @Inject
    lateinit var resourcesIndexRepo: ResourcesIndexRepo

    @Inject
    lateinit var previewsStorageRepo: PreviewStorageRepo

    @Inject
    lateinit var stringProvider: StringProvider

    @Inject
    lateinit var preferences: Preferences

    @Inject
    @Named(Constants.DI.MESSAGE_FLOW_NAME)
    lateinit var messageFlow: MutableSharedFlow<Message>

    private lateinit var devices: List<Path>

    override fun onFirstViewAttach() {
        Log.d(FOLDERS_SCREEN, "first view attached in RootsPresenter")
        super.onFirstViewAttach()

        viewState.init()
        presenterScope.launch {
            viewState.setProgressVisibility(true, "Loading")
            val folders = foldersRepo.provideWithMissing()
            devices = listDevices()

            viewState.toastFailedPath(folders.failed)

            viewState.updateFoldersTree(devices, folders.succeeded)
            viewState.setProgressVisibility(false)

            messageFlow.onEach { message ->
                when (message) {
                    is Message.KindDetectFailed -> viewState.toastIndexFailedPath(
                        message.path
                    )
                }
            }.launchIn(presenterScope)

            if (rescanRoots) {
                viewState.openRootsScanDialog()
                return@launch
            }

            if (!preferences.get(PreferenceKey.WasRootsScanShown) &&
                folders.succeeded.keys.isEmpty()
            ) {
                preferences.set(PreferenceKey.WasRootsScanShown, true)
                viewState.openRootsScanDialog()
            }
        }
    }

    fun onNavigateBtnClick(node: FolderNode) {
        when (node) {
            is DeviceNode -> {}
            is RootNode -> {
                router.navigateTo(
                    Screens.ResourcesScreen(
                        RootAndFav(node.path.toString(), null)
                    )
                )
            }
            is FavoriteNode -> {
                router.navigateTo(
                    Screens.ResourcesScreen(
                        RootAndFav(node.root.toString(), node.path.toString())
                    )
                )
            }
        }
    }

    fun onFoldersTreeAddFavoriteBtnClick(node: FolderNode) {
        viewState.openRootPickerDialog(node.path)
    }

    fun onAddRootBtnClick() {
        viewState.openRootPickerDialog(null)
    }

    fun onPickRootBtnClick(path: Path, rootNotFavorite: Boolean) =
        presenterScope.launch(NonCancellable) {
            val folders = foldersRepo.provideFolders()

            if (rootNotFavorite) {
                // adding path as root
                if (folders.keys.contains(path)) {
                    viewState.toastRootIsAlreadyPicked()
                } else {
                    addRoot(path)
                }
            } else {
                // adding path as favorite
                if (folders.values.flatten().contains(path)) {
                    viewState.toastFavoriteIsAlreadyPicked()
                } else {
                    addFavorite(path)
                }
            }
        }

    fun onRootsFound(roots: List<Path>) = presenterScope.launch(NonCancellable) {
        roots.forEach { root ->
            foldersRepo.addRoot(root)
        }
        viewState.updateFoldersTree(devices, foldersRepo.provideFolders())
    }

    fun onForgetBtnClick(node: FolderNode) {
        viewState.openConfirmForgetFolderDialog(node)
    }

    fun onForgetRoot(root: Path, deleteFromMemory: Boolean) =
        presenterScope.launch(NonCancellable) {
            viewState.setProgressVisibility(true, "Forgetting root")
            if (deleteFromMemory) {
                Log.d(
                    FOLDERS_TREE,
                    "forgetting and deleting root folder $root"
                )
                foldersRepo.deleteRoot(root)
            } else {
                Log.d(
                    FOLDERS_TREE,
                    "forgetting root folder $root"
                )
                foldersRepo.forgetRoot(root)
            }
            viewState.updateFoldersTree(devices, foldersRepo.provideFolders())
            viewState.setProgressVisibility(false)
        }

    fun onForgetFavorite(root: Path, favorite: Path, deleteFromMemory: Boolean) =
        presenterScope.launch(NonCancellable) {
            viewState.setProgressVisibility(true, "Forgetting favorite")
            if (deleteFromMemory) {
                Log.d(
                    FOLDERS_TREE,
                    "forgetting and deleting favorite $favorite"
                )
                foldersRepo.deleteFavorite(root, favorite)
            } else {
                Log.d(
                    FOLDERS_TREE,
                    "forgetting favorite $favorite"
                )
                foldersRepo.forgetFavorite(root, favorite)
            }
            viewState.updateFoldersTree(devices, foldersRepo.provideFolders())
            viewState.setProgressVisibility(false)
        }

    private suspend fun addRoot(root: Path) {
        viewState.setProgressVisibility(true, "Adding folder")
        Log.d(FOLDERS_SCREEN, "root $root added in RootsPresenter")
        val path = root.toRealPath()
        val folders = foldersRepo.provideFolders()

        if (folders.containsKey(path)) {
            throw AssertionError("Path must be checked in RootPicker")
        }

        foldersRepo.addRoot(path)

        viewState.toastIndexingCanTakeMinutes()

        viewState.setProgressVisibility(true, "Indexing")
        val index = resourcesIndexRepo.provide(root)
        previewsStorageRepo.provide(root)
        index.reindex()

        viewState.setProgressVisibility(false)

        viewState.updateFoldersTree(devices, foldersRepo.provideFolders())
    }

    private fun addFavorite(favorite: Path) =
        presenterScope.launch(NonCancellable) {
            viewState.setProgressVisibility(true, "Adding folder")
            Log.d(FOLDERS_SCREEN, "favorite $favorite added in RootsPresenter")
            val path = favorite.toRealPath()
            val folders = foldersRepo.provideFolders()

            val root = folders.keys.find { path.startsWith(it) }
                ?: throw IllegalStateException(
                    "Can't add favorite if it's root is not added"
                )

            val relative = root.relativize(path)
            if (folders[root]!!.contains(relative)) {
                throw AssertionError("Path must be checked in RootPicker")
            }

            foldersRepo.addFavorite(root, relative)

            viewState.updateFoldersTree(devices, foldersRepo.provideFolders())
            viewState.setProgressVisibility(false)
        }

    fun onBackClick() {
        Log.d(FOLDERS_SCREEN, "[back] clicked")
        router.exit()
    }
}
