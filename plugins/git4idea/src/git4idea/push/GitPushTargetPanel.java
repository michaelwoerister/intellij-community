/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.push;

import com.intellij.dvcs.push.PushTargetPanel;
import com.intellij.dvcs.push.ui.*;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import git4idea.GitRemoteBranch;
import git4idea.commands.Git;
import git4idea.commands.GitCommandResult;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.validators.GitRefNameValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.text.ParseException;
import java.util.Comparator;
import java.util.List;

public class GitPushTargetPanel extends PushTargetPanel<GitPushTarget> {

  private static final Logger LOG = Logger.getInstance(GitPushTargetPanel.class);

  private static final Comparator<GitRemoteBranch> REMOTE_BRANCH_COMPARATOR = new MyRemoteBranchComparator();
  private static final String SEPARATOR = " : ";

  @NotNull private final GitPushSupport myPushSupport;
  @NotNull private final GitRepository myRepository;
  @NotNull private final Git myGit;

  @NotNull private final VcsEditableTextComponent myTargetRenderer;
  @NotNull private final PushTargetTextField myTargetEditor;
  @NotNull private final VcsLinkedTextComponent myRemoteRenderer;
  @NotNull private final Project myProject;

  @Nullable private GitPushTarget myCurrentTarget;
  @Nullable private String myError;
  @Nullable private Runnable myFireOnChangeAction;

  public GitPushTargetPanel(@NotNull GitPushSupport support, @NotNull GitRepository repository, @Nullable GitPushTarget defaultTarget) {
    myPushSupport = support;
    myRepository = repository;
    myGit = ServiceManager.getService(Git.class);
    myProject = myRepository.getProject();

    myTargetRenderer = new VcsEditableTextComponent("", null);
    myTargetEditor = new PushTargetTextField(repository.getProject(), getTargetNames(myRepository), "");
    myRemoteRenderer = new VcsLinkedTextComponent("", new VcsLinkListener() {
      @Override
      public void hyperlinkActivated(@NotNull DefaultMutableTreeNode sourceNode, @NotNull MouseEvent event) {
        if (myRepository.getRemotes().isEmpty()) {
          showDefineRemoteDialog();
        }
        else {
          showRemoteSelector(event);
        }
      }
    });

    setLayout(new BorderLayout());
    setOpaque(false);
    JPanel remoteAndSeparator = new JPanel(new BorderLayout());
    remoteAndSeparator.setOpaque(false);
    remoteAndSeparator.add(myRemoteRenderer, BorderLayout.CENTER);
    remoteAndSeparator.add(new JBLabel(SEPARATOR), BorderLayout.EAST);

    add(remoteAndSeparator, BorderLayout.WEST);
    add(myTargetEditor, BorderLayout.CENTER);

    updateComponents(defaultTarget);
  }

  private void updateComponents(@Nullable GitPushTarget target) {
    myCurrentTarget = target;

    String initialBranch = "";
    String initialRemote = "";
    boolean noRemotes = myRepository.getRemotes().isEmpty();
    if (target == null) {
      if (myRepository.getCurrentBranch() == null) {
        myError = "Detached HEAD";
      }
      else if (myRepository.isFresh()) {
        myError = "Empty repository";
      }
      else if (!noRemotes) {
        myError = "Can't push";
      }
    }
    else {
      initialBranch = getTextFieldText(target);
      initialRemote = target.getBranch().getRemote().getName();
    }

    myTargetRenderer.updateLinkText(initialBranch);
    myTargetEditor.setText(initialBranch);
    myRemoteRenderer.updateLinkText(noRemotes ? "Define remote" : initialRemote);

    myTargetEditor.setVisible(!noRemotes);
  }

  private void showDefineRemoteDialog() {
    GitDefineRemoteDialog dialog = new GitDefineRemoteDialog(myRepository.getProject());
    if (dialog.showAndGet()) {
      String name = dialog.getRemoteName();
      String url = dialog.getRemoteUrl();
      String error = validateRemoteUnderModal(name, url);
      if (error != null) {
        LOG.warn(String.format("Invalid remote. Name: [%s], URL: [%s], error: %s", name, url, error));
        Messages.showErrorDialog(myRepository.getProject(), error, "Invalid Remote URL");
      }
      else {
        addRemoteUnderModal(name, url);
      }
    }
  }

  @Nullable
  private String validateRemoteUnderModal(final String name, final String url) {
    if (url.isEmpty()) {
      return "URL can't be empty";
    }
    if (!GitRefNameValidator.getInstance().checkInput(name)) {
      return "Remote name is invalid";
    }

    final Ref<String> error = Ref.create();
    ProgressManager.getInstance().run(new Task.Modal(myRepository.getProject(), "Checking URL...", false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        final GitCommandResult result = myGit.lsRemote(myRepository.getProject(), VfsUtilCore.virtualToIoFile(myRepository.getRoot()), url);
        if (!result.success()) {
          error.set("Remote URL is invalid: " + result.getErrorOutputAsHtmlString());
        }
      }
    });
    return error.get();
  }

  private void addRemoteUnderModal(@NotNull final String remoteName, @NotNull final String remoteUrl) {
    ProgressManager.getInstance().run(new Task.Modal(myRepository.getProject(), "Adding remote...", false) {
      private GitCommandResult myResult;

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        myResult = myGit.addRemote(myRepository, remoteName, remoteUrl);
        myRepository.update();
      }

      @Override
      public void onSuccess() {
        if (myResult.success()) {
          updateComponents(myPushSupport.getDefaultTarget(myRepository));
          if (myFireOnChangeAction != null) {
            myFireOnChangeAction.run();
          }
        }
        else {
          String message = "Couldn't add remote: " + myResult.getErrorOutputAsHtmlString();
          LOG.warn(message);
          Messages.showErrorDialog(myProject, message, "Add Remote");
        }
      }
    });
  }

  private void showRemoteSelector(@NotNull MouseEvent event) {
    final List<String> remotes = getRemotes();
    if (remotes.size() <= 1) {
      return;
    }

    ListPopup popup = JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<String>(null, remotes) {
      @Override
      public PopupStep onChosen(String selectedValue, boolean finalChoice) {
        myRemoteRenderer.updateLinkText(selectedValue);
        if (myFireOnChangeAction != null) {
          myFireOnChangeAction.run();
        }
        return super.onChosen(selectedValue, finalChoice);
      }
    });
    popup.show(new RelativePoint(event));
  }

  @NotNull
  private List<String> getRemotes() {
    return ContainerUtil.map(myRepository.getRemotes(), new Function<GitRemote, String>() {
      @Override
      public String fun(GitRemote remote) {
        return remote.getName();
      }
    });
  }

  @Override
  public void render(@NotNull ColoredTreeCellRenderer renderer, boolean isSelected, boolean isActive, @Nullable String forceRenderedText) {

    SimpleTextAttributes targetTextAttributes = PushLogTreeUtil.addTransparencyIfNeeded(SimpleTextAttributes.REGULAR_ATTRIBUTES, isActive);
    if (myError != null) {
      renderer.append(myError, PushLogTreeUtil.addTransparencyIfNeeded(SimpleTextAttributes.ERROR_ATTRIBUTES, isActive));
    }
    else {
      String currentRemote = myRemoteRenderer.getText();
      List<String> remotes = getRemotes();
      if (remotes.isEmpty() || remotes.size() > 1) {
        myRemoteRenderer.setSelected(isSelected);
        myRemoteRenderer.setTransparent(!remotes.isEmpty() && !isActive);
        myRemoteRenderer.render(renderer);
      }
      else {
        renderer.append(currentRemote, targetTextAttributes);
      }
      if (!remotes.isEmpty()) {
        renderer.append(SEPARATOR, targetTextAttributes);
        if (forceRenderedText != null) {
          //if sync typing available we need to emulate editor changes
          myTargetEditor.setText(forceRenderedText);
          renderer.append(forceRenderedText);
          return;
        }
        GitPushTarget target = getValue();
        if (target != null && target.isNewBranchCreated()) {
          renderer.append("+", PushLogTreeUtil.addTransparencyIfNeeded(SimpleTextAttributes.SYNTHETIC_ATTRIBUTES, isActive), this);
        }
        myTargetRenderer.setSelected(isSelected);
        myTargetRenderer.setTransparent(!isActive);
        myTargetRenderer.render(renderer);
      }
    }
  }

  @Nullable
  @Override
  public GitPushTarget getValue() {
    return myCurrentTarget;
  }

  @NotNull
  private static String getTextFieldText(@Nullable GitPushTarget target) {
    return (target != null ? target.getBranch().getNameForRemoteOperations() : "");
  }

  @Override
  public void fireOnCancel() {
    myTargetEditor.setText(getTextFieldText(myCurrentTarget));
  }

  @Override
  public void fireOnChange() {
    //any changes are senselessly if no remotes
    if (myError != null || myRepository.getRemotes().isEmpty()) return;
    String remoteName = myRemoteRenderer.getText();
    String branchName = myTargetEditor.getText();
    try {
      myCurrentTarget = GitPushTarget.parse(myRepository, remoteName, branchName);
      myTargetRenderer.updateLinkText(branchName);
    }
    catch (ParseException e) {
      LOG.error("Invalid remote name shouldn't be allowed. [" + remoteName + ", " + branchName + "]", e);
    }
  }

  @Nullable
  @Override
  public ValidationInfo verify() {
    if (myError != null) {
      return new ValidationInfo(myError, myTargetEditor);
    }
    try {
      GitPushTarget.parse(myRepository, myRemoteRenderer.getText(), myTargetEditor.getText());
      return null;
    }
    catch (ParseException e) {
      return new ValidationInfo(e.getMessage(), myTargetEditor);
    }
  }

  @SuppressWarnings("NullableProblems")
  @Override
  public void setFireOnChangeAction(@NotNull Runnable action) {
    myFireOnChangeAction = action;
  }

  @NotNull
  private static List<String> getTargetNames(@NotNull GitRepository repository) {
    List<GitRemoteBranch> remoteBranches = ContainerUtil.sorted(repository.getBranches().getRemoteBranches(), REMOTE_BRANCH_COMPARATOR);
    return ContainerUtil.map(remoteBranches, new Function<GitRemoteBranch, String>() {
      @Override
      public String fun(GitRemoteBranch branch) {
        return branch.getNameForRemoteOperations();
      }
    });
  }

  private static class MyRemoteBranchComparator implements Comparator<GitRemoteBranch> {
    @Override
    public int compare(@NotNull GitRemoteBranch o1, @NotNull GitRemoteBranch o2) {
      String remoteName1 = o1.getRemote().getName();
      String remoteName2 = o2.getRemote().getName();
      int remoteComparison = remoteName1.compareTo(remoteName2);
      if (remoteComparison != 0) {
        if (remoteName1.equals(GitRemote.ORIGIN_NAME)) {
          return -1;
        }
        if (remoteName2.equals(GitRemote.ORIGIN_NAME)) {
          return 1;
        }
        return remoteComparison;
      }
      return o1.getNameForLocalOperations().compareTo(o2.getNameForLocalOperations());
    }
  }

  @Override
  public void addTargetEditorListener(@NotNull final PushTargetEditorListener listener) {
    myTargetEditor.addDocumentListener(new DocumentAdapter() {
      @Override
      public void documentChanged(DocumentEvent e) {
        super.documentChanged(e);
        listener.onTargetInEditModeChanged(myTargetEditor.getText());
      }
    });
  }
}
