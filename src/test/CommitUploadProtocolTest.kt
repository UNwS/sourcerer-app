// Copyright 2017 Sourcerer Inc. All Rights Reserved.
// Author: Liubov Yaronskaya (lyaronskaya@sourcerer.io)

package test

import app.hashers.CommitHasher
import app.api.MockApi
import app.config.MockConfigurator
import app.model.*
import app.utils.RepoHelper
import org.eclipse.jgit.api.Git
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.*
import java.io.File
import java.util.stream.StreamSupport.stream
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CommitUploadProtocolTest : Spek({
    val repoPath = "./tmp_repo/.git"
    val git = Git.init().setGitDir(File(repoPath)).call()
    val gitHasher = Git.open(File(repoPath))
    val localRepo: LocalRepo = LocalRepo(repoPath)
    val initialCommit = Commit(git.commit().setMessage("Initial commit.").call())
    val repoRehash = RepoHelper.calculateRepoRehash(initialCommit.rehash, localRepo)
    var repo = Repo(rehash = repoRehash, initialCommitRehash = initialCommit.rehash)
    repo.commits = listOf(initialCommit)

    fun getRepoRehash(git: Git, localRepo: LocalRepo): String {

        val initialRevCommit = stream(git.log().call().spliterator(), false)
                               .toList().first()
        val initialCommit = Commit(initialRevCommit)
        val repoRehash = RepoHelper.calculateRepoRehash(initialCommit.rehash,
                localRepo)
        return repoRehash
    }

    fun getLastCommit(git: Git): Commit {
        val revCommits = stream(git.log().call().spliterator(), false).toList()
        val lastCommit = Commit(revCommits.first())
        return lastCommit
    }

    given("empty repo") {
        repo.commits = listOf(getLastCommit(git))

        val mockApi = MockApi(mockRepo = repo)
        val mockConfigurator = MockConfigurator(mockRepos = mutableListOf(repo))
        CommitHasher(localRepo, repo, mockApi, mockConfigurator, gitHasher).update()

        it("doesn't send added commits") {
            assertEquals(0, mockApi.receivedAddedCommits.size)
        }

        it("doesn't send deleted commits") {
            assertEquals(0, mockApi.receivedDeletedCommits.size)
        }
    }

    given("happy path: added one commit") {
        repo.commits = listOf(getLastCommit(git))
        val mockApi = MockApi(mockRepo = repo)
        val mockConfigurator = MockConfigurator(mockRepos = mutableListOf(repo))

        val revCommit = git.commit().setMessage("Second commit.").call()
        val addedCommit = Commit(revCommit)
        CommitHasher(localRepo, repo, mockApi, mockConfigurator, gitHasher).update()

        it("doesn't send deleted commits") {
            assertEquals(0, mockApi.receivedDeletedCommits.size)
        }

        it("posts one commit as added") {
            assertEquals(1, mockApi.receivedAddedCommits.size)
        }

        it("should be that the posted commit is added one") {
            assertEquals(addedCommit, mockApi.receivedAddedCommits.last())
        }
    }

    given("happy path: added a few new commits") {
        repo.commits = listOf(getLastCommit(git))
        val mockApi = MockApi(mockRepo = repo)
        val mockConfigurator = MockConfigurator(mockRepos = mutableListOf(repo))

        val otherAuthorsNames = listOf("a", "b", "a")
        val otherAuthorsEmails = listOf("a@a", "b@b", "a@a")
        for (i in 0..2) {
            git.commit().setMessage("Create $i.").setAuthor(otherAuthorsNames.get(i), otherAuthorsEmails.get(i)).call()
        }
        val authorCommits = mutableListOf<Commit>()
        for (i in 0..4) {
            val message = "Created $i by author."
            val revCommit = git.commit().setMessage(message).call()
            authorCommits.add(Commit(revCommit))
        }
        CommitHasher(localRepo, repo, mockApi, mockConfigurator, gitHasher).update()

        it("posts five commits as added") {
            assertEquals(5, mockApi.receivedAddedCommits.size)
        }

        it("doesn't send deleted commits ") {
            assertEquals(0, mockApi.receivedDeletedCommits.size)
        }

        it("processes author's commits") {
            assertEquals(authorCommits.asReversed(), mockApi.receivedAddedCommits)
        }
    }

    given("fork event") {

        val forkedRepoPath = "./forked_repo/"
        val originalRepoPath = "./original_repo/"
        val forked = Git.cloneRepository()
                .setURI("https://github.com/yaronskaya/sourcerer-app.git")
                .setDirectory(File(forkedRepoPath))
                .call()
        val original = Git.cloneRepository()
                .setURI("https://github.com/sourcerer-io/sourcerer-app.git")
                .setDirectory(File(originalRepoPath))
                .call()
        val forkedLocalRepo = LocalRepo(forkedRepoPath)
        val originalLocalRepo = LocalRepo(originalRepoPath)

        val forkedRepoRehash = getRepoRehash(forked, forkedLocalRepo)
        val originalRepoRehash = getRepoRehash(original, originalLocalRepo)

        it("assigns different hashes for the original and the forked repos") {
            assertNotEquals(originalRepoRehash, forkedRepoRehash)
        }

        forked.repository.close()
        forked.close()
        original.repository.close()
        original.close()

    }

    given("lost server") {
        repo.commits = listOf(getLastCommit(git))
        var mockApi = MockApi(mockRepo = repo)
        var mockConfigurator = MockConfigurator(mockRepos = mutableListOf(repo))

        // Add some commits.
        val addedCommits = mutableListOf<Commit>()
        for (i in 0..3) {
            val message = "Created $i by author."
            val revCommit = git.commit().setMessage(message).call()
            addedCommits.add(Commit(revCommit))
        }
        CommitHasher(localRepo, repo, mockApi, mockConfigurator, gitHasher).update()

        // Remove one commit from server history.
        val removedCommit = addedCommits.removeAt(1)
        repo.commits = addedCommits.toList().asReversed()
        mockConfigurator = MockConfigurator(mockRepos = mutableListOf(repo))
        mockApi = MockApi(mockRepo = repo)
        CommitHasher(localRepo, repo, mockApi, mockConfigurator, gitHasher).update()

        it("adds posts one commit as added and received commit is lost one") {
            assertEquals(1, mockApi.receivedAddedCommits.size)
            assertEquals(removedCommit, mockApi.receivedAddedCommits.last())
        }

        it("doesn't posts deleted commits") {
            assertEquals(0, mockApi.receivedDeletedCommits.size)
        }

    }

    Runtime.getRuntime().exec("src/test/delete_repo.sh").waitFor()
})
