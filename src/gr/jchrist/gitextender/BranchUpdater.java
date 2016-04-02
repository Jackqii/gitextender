package gr.jchrist.gitextender;

import com.intellij.history.Label;
import com.intellij.history.LocalHistory;
import com.intellij.history.LocalHistoryAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.VcsAnnotationRefresher;
import com.intellij.openapi.vcs.changes.committed.CommittedChangesCache;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.vcs.update.*;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.Consumer;
import com.intellij.util.ContentUtilEx;
import com.intellij.util.WaitForProgressToShow;
import git4idea.GitRevisionNumber;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.merge.MergeChangeCollector;
import git4idea.repo.GitBranchTrackInfo;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * @author jchrist
 * @since 2016/03/27
 */
public class BranchUpdater {
    private final Git git;
    private final GitRepository repo;

    private final String repoName;
    private final String remoteBranchName;
    private final String localBranchName;

    private GitRevisionNumber start;
    private Label before;
    private LocalHistoryAction myLocalHistoryAction;
    private UpdatedFiles updatedFiles;
    private BranchUpdateResult branchUpdateResult;

    public BranchUpdater(Git git, GitRepository repo, String repoName, GitBranchTrackInfo info) {
        this.git = git;
        this.repo = repo;
        this.repoName = repoName;

        this.remoteBranchName = info.getRemoteBranch().getNameForLocalOperations();
        this.localBranchName = info.getLocalBranch().getName();
        this.branchUpdateResult = new BranchUpdateResult();
    }

    @NotNull
    public static String prepareNotificationWithUpdateInfo(UpdatedFiles updatedFiles) {
        StringBuffer text = new StringBuffer();
        final List<FileGroup> groups = updatedFiles.getTopLevelGroups();
        for (FileGroup group : groups) {
            appendGroup(text, group);
        }
        return text.toString();
    }

    public static void appendGroup(final StringBuffer text, final FileGroup group) {
        final int s = group.getFiles().size();
        if (s > 0) {
            text.append("\n");
            text.append(s).append(" ").append(StringUtil.pluralize("File", s)).append(" ").append(group.getUpdateName());
        }

        final List<FileGroup> list = group.getChildren();
        for (FileGroup g : list) {
            appendGroup(text, g);
        }
    }

    public BranchUpdateResult update() {
        checkout();
        if (!branchUpdateResult.isCheckoutSuccess()) {
            return this.branchUpdateResult;
        }

        merge();
        return this.branchUpdateResult;
    }

    protected void checkout() {
        GitCommand checkout = GitCommand.CHECKOUT;
        GitLineHandler checkoutHandler = new GitLineHandler(repo.getProject(), repo.getRoot(), checkout);
        checkoutHandler.addParameters(localBranchName);
        branchUpdateResult.checkoutResult = git.runCommand(checkoutHandler);
    }

    protected void merge() {
        //let's prepare for the merge: find what files we have now
        prepareMerge();

        mergeFastForwardOnly();
        if (!branchUpdateResult.isMergeFastForwardSuccess()) {
            //todo this should be controlled with a user setting flag
            //because it might be dangerous. Leave it off for now
            //mergeAbortIfFailed();
        }

        if (branchUpdateResult.isMergeSuccess()) {
            afterMerge();
        }
    }

    protected void mergeFastForwardOnly() {
        //do NOT rebase from remote after all :) just merge with -ff only
        GitCommand merge = GitCommand.MERGE;
        GitLineHandler mergeHandler = new GitLineHandler(repo.getProject(), repo.getRoot(), merge);
        mergeHandler.addParameters("--ff-only");
        mergeHandler.addParameters(remoteBranchName);
        branchUpdateResult.mergeFastForwardResult = git.runCommand(mergeHandler);
    }

    protected void mergeAbortIfFailed() {
        //try simple merging, abort if failed
        GitCommand merge = GitCommand.MERGE;
        GitLineHandler mergeHandler = new GitLineHandler(repo.getProject(), repo.getRoot(), merge);
        mergeHandler.addParameters(remoteBranchName);
        branchUpdateResult.mergeSimpleResult = git.runCommand(mergeHandler);

        if (branchUpdateResult.isMergeSuccess()) {
            return;
        }

        abortMerge();
    }

    protected void abortMerge() {
        //we failed, now abort the process
        GitCommand abort = GitCommand.MERGE;
        GitLineHandler abortHandler = new GitLineHandler(repo.getProject(), repo.getRoot(), abort);
        abortHandler.addParameters("--abort");
        branchUpdateResult.abortResult = git.runCommand(abortHandler);

        if (!branchUpdateResult.isAbortSucceeded()) {
            //We have really messed up! What can I do now?
        }
    }

    protected void prepareMerge() {
        //record the starting revision number
        try {
            start = GitRevisionNumber.resolve(repo.getProject(), repo.getRoot(), "HEAD");
        } catch (Exception e) {
            //we failed to get a revision number, that shouldn't stop us from updating the branch
            start = null;
        }

        final String beforeLabel = "Updating:" + repoName + " " + localBranchName;
        before = LocalHistory.getInstance().putSystemLabel(repo.getProject(), "Before " + beforeLabel);
        myLocalHistoryAction = LocalHistory.getInstance().startAction(beforeLabel);

        //keep track of all updated files
        updatedFiles = UpdatedFiles.create();
    }

    protected void afterMerge() {
        //we managed to update the branch, so let's try to get which files were updated
        gatherChanges();
        refreshFiles();
        myLocalHistoryAction.finish();

        showUpdatedFiles();
    }

    protected void gatherChanges() {
        MergeChangeCollector collector = new MergeChangeCollector(repo.getProject(), repo.getRoot(), start);
        final ArrayList<VcsException> exceptions = new ArrayList<VcsException>();
        collector.collect(updatedFiles, exceptions);
    }

    protected void refreshFiles() {
        //request that we update repo for all updated files
        RefreshVFsSynchronously.updateAllChanged(updatedFiles);

        //also notify annotations
        final VcsAnnotationRefresher refresher = repo.getProject().getMessageBus()
                .syncPublisher(VcsAnnotationRefresher.LOCAL_CHANGES_CHANGED);
        UpdateFilesHelper.iterateFileGroupFilesDeletedOnServerFirst(updatedFiles, new UpdateFilesHelper.Callback() {
            @Override
            public void onFile(String filePath, String groupId) {
                refresher.dirty(filePath);
            }
        });
    }

    private void showUpdatedFiles() {
        //if no files were updated, then there's nothing else to do
        if (!updatedFiles.isEmpty()) {
            final String notificationWithUpdateInfo = prepareNotificationWithUpdateInfo(updatedFiles);

            final Label after = LocalHistory.getInstance().putSystemLabel(repo.getProject(),
                    "After updating:" + repoName + " " + localBranchName);

            final UpdateInfoTree tree = getUpdateInfoTree(
                    repo.getProject(), updatedFiles,
                    repoName, localBranchName, remoteBranchName,
                    before, after);
            final CommittedChangesCache cache = CommittedChangesCache.getInstance(repo.getProject());
            cache.processUpdatedFiles(updatedFiles, new Consumer<List<CommittedChangeList>>() {
                @Override
                public void consume(List<CommittedChangeList> incomingChangeLists) {
                    tree.setChangeLists(incomingChangeLists);
                }
            });

            WaitForProgressToShow.runOrInvokeLaterAboveProgress(new Runnable() {
                @Override
                public void run() {
                    showUpdateTree(repo.getProject(), tree, repoName, localBranchName, remoteBranchName);
                    VcsBalloonProblemNotifier.showOverChangesView(repo.getProject(), "VCS Update Finished" +
                            notificationWithUpdateInfo, MessageType.INFO);
                }
            }, null, repo.getProject());
        }
    }

    public UpdateInfoTree getUpdateInfoTree(
            Project project, UpdatedFiles updatedFiles,
            String repoName, String localBranchName, String remoteBranchName,
            Label before, Label after
    ) {
        if (!project.isOpen() || project.isDisposed()) return null;
        ContentManager contentManager = getContentManager(project);
        if (contentManager == null) {
            return null;  // content manager is made null during dispose; flag is set later
        }
        ActionInfo actionInfo = ActionInfo.UPDATE;
        RestoreUpdateTree restoreUpdateTree = RestoreUpdateTree.getInstance(project);
        restoreUpdateTree.registerUpdateInformation(updatedFiles, actionInfo);
        final String text = "GitExtender updated Repo:" + repoName + " " + localBranchName + "->" + remoteBranchName;
        //don't use the one from project level vcs manager, since we want to split creation of tree from showing
        /*final UpdateInfoTree updateInfoTree = ProjectLevelVcsManagerEx.getInstanceEx(project).
                showUpdateProjectInfo(updatedFiles, text, actionInfo, false);*/

        final UpdateInfoTree updateInfoTree = new UpdateInfoTree(contentManager, project, updatedFiles, text, actionInfo);
        //todo the update info tree is not quite an _update_ info tree
        //files changed in branches other than the one checked out will not show correct changes
        //however, leave the actions as is, this should be fixed in a future version (in a way that I don't know yet :)
        /*{
            @Override
            protected void addActionsTo(DefaultActionGroup group) {
                //do not add any actions, since they will be wrong anyway
                //the changed files might be in another branch than the one checked out, so no real diffing can be done
            }
        };*/

        updateInfoTree.setBefore(before);
        updateInfoTree.setAfter(after);

        updateInfoTree.setCanGroupByChangeList(false);

        return updateInfoTree;
    }

    private void showUpdateTree(
            final Project project,
            final UpdateInfoTree updateInfoTree,
            String repoName, String localBranchName, String remoteBranchName
    ) {
        if (!project.isOpen() || project.isDisposed()) return;
        ContentManager contentManager = getContentManager(project);
        if (contentManager == null) {
            return; // content manager is made null during dispose; flag is set later
        }
        final String tabName = repoName + " " + localBranchName + "->" + remoteBranchName;
        ContentUtilEx.addTabbedContent(contentManager, updateInfoTree, "Update Info", tabName, true, updateInfoTree);
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS).activate(null);
        updateInfoTree.expandRootChildren();
    }

    public ContentManager getContentManager(Project project) {
        ToolWindow changes = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS);
        return changes.getContentManager();
    }

    @NotNull
    public String getLocalBranchName() {
        return localBranchName;
    }

    @NotNull
    public String getRemoteBranchName() {
        return remoteBranchName;
    }

    @NotNull
    public BranchUpdateResult getBranchUpdateResult() {
        return branchUpdateResult;
    }

    public static class BranchUpdateResult {
        protected GitCommandResult checkoutResult;
        protected GitCommandResult mergeFastForwardResult;
        protected GitCommandResult mergeSimpleResult;
        protected GitCommandResult abortResult;

        public boolean isSuccess() {
            //we're successful if we checked out and
            //we merged either by fast-forward, or with simple merge
            return isCheckoutSuccess() && isMergeSuccess();
        }

        public boolean isCheckoutSuccess() {
            return checkoutResult != null && checkoutResult.success();
        }

        public boolean isCheckoutError() {
            return !isCheckoutSuccess();
        }

        public boolean isMergeSuccess() {
            return isMergeFastForwardSuccess() || isMergeSimpleSuccess();
        }

        public boolean isMergeError() {
            return !isMergeSuccess();
        }

        public boolean isMergeFastForwardSuccess() {
            return mergeFastForwardResult != null && mergeFastForwardResult.success();
        }

        public boolean isMergeSimpleSuccess() {
            return mergeSimpleResult != null && mergeSimpleResult.success();
        }

        public boolean isAbortSucceeded() {
            return abortResult != null && abortResult.success();
        }

        public GitCommandResult getCheckoutResult() {
            return checkoutResult;
        }

        public GitCommandResult getMergeFastForwardResult() {
            return mergeFastForwardResult;
        }

        public GitCommandResult getMergeSimpleResult() {
            return mergeSimpleResult;
        }

        public GitCommandResult getAbortResult() {
            return abortResult;
        }
    }
}