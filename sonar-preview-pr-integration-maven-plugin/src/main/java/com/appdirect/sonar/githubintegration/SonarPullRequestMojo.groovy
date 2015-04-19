import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.eclipse.egit.github.core.CommitComment
import org.eclipse.egit.github.core.CommitFile
import org.eclipse.egit.github.core.Repository
import org.eclipse.egit.github.core.RepositoryCommit
import org.eclipse.egit.github.core.client.GitHubClient
import org.eclipse.egit.github.core.service.PullRequestService
import org.eclipse.egit.github.core.service.RepositoryService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.sonar.wsclient.issue.Issue as SonarIssue
import org.sonar.wsclient.issue.Issues as SonarIssues
import org.sonar.wsclient.issue.internal.IssueJsonParser

import com.appdirect.sonar.githubintegration.ComponentConverter
import com.appdirect.sonar.githubintegration.LinePositioner
import com.appdirect.sonar.githubintegration.OneToOneLinePositioner
import com.appdirect.sonar.githubintegration.PatchLinePositioner
import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import com.google.common.net.UrlEscapers

@Mojo(name = 'publish', aggregator = true)
class SonarPullRequestMojo extends AbstractMojo {
	private static final Logger log = LoggerFactory.getLogger(SonarPullRequestMojo)
	/**
	 * The projects in the reactor.
	 */
	@Parameter(readonly = true, defaultValue = '${reactorProjects}')
	List<MavenProject> reactorProjects

	/**
	 * Sonar Base URL.
	 */
	@Parameter(property = 'sonar.host.url', defaultValue = 'http://localhost:9000/')
	String sonarHostUrl

	/**
	 * Sonar JSON report path.
	 */
	@Parameter(property = 'sonar.report.path', defaultValue = '${project.build.directory}/sonar/sonar-report.json')
	String sonarReportPath

	/**
	 * Set OAuth2 token
	 */
	@Parameter(property = 'github.oauth2', required = true)
	String oauth2

	/**
	 * Github pull request ID
	 */
	@Parameter(property = 'github.pullRequestId', required = true)
	int pullRequestId

	/**
	 * Github repository owner
	 */
	@Parameter(property = 'github.repositoryOwner', required = true)
	String repositoryOwner

	/**
	 * Github repository name
	 */
	@Parameter(property = 'github.repositoryName', required = true)
	String repositoryName

	/**
	 * Branch to be used.
	 */
	@Parameter(property = "sonar.branch")
	String sonarBranch

	public void execute() throws MojoExecutionException {
		GitHubClient client = new GitHubClient().setOAuth2Token(oauth2)
		PullRequestService pullRequestService = new PullRequestService(client)

		RepositoryService repositoryService = new RepositoryService(client)
		Repository repository = repositoryService.getRepository(repositoryOwner, repositoryName)

		List<CommitFile> files = pullRequestService.getFiles(repository, pullRequestId)
		ComponentConverter componentConverter = getRelatedComponents(files)

		log.info('{} files affected', componentConverter.size())

		List<SonarIssue> issues = getIssues(componentConverter);
		log.info( "Found {} issues", issues.size());

		Multimap<String, SonarIssue> fileViolations = issues.inject(LinkedHashMultimap.create()) { violations, issue ->
			String path = componentConverter.componentToPath(issue.componentKey())
			violations << [ (path): issue ]
		}

		Map<String, LinePositioner> linePositioners;
		try {
			linePositioners = createLinePositioners(files);
		} catch (IOException e) {
			throw new MojoExecutionException('Unable to get commits on github', e );
		}

		Map<String, String> filesSha = getFilesSha(files)

		removeIssuesOutsideBounds(fileViolations, linePositioners);
		log.info('Found {} files with issues ({} issues) ', fileViolations.keySet().size(), fileViolations.size())

		List<CommitComment> comments = pullRequestService.getComments(repository, pullRequestId)
		removeIssuesAlreadyReported(comments, fileViolations, linePositioners)
		log.info('Files with new issues: {} ({} issues)', fileViolations.keySet().size(), fileViolations.size())

		List<RepositoryCommit> commits = pullRequestService.getCommits(repository, pullRequestId)
		recordGit(commits, pullRequestService, repository, fileViolations, linePositioners, filesSha)
	}

	private Map<String, String> getFilesSha(List<CommitFile> files) {
		files.inject([:]) { map, commitFile ->
			map << [ (commitFile.filename): commitFile.blobUrl.replaceAll( ".*blob/", "" ).replaceAll( "/.*", "" ) ]
		}
	}

	private void removeIssuesOutsideBounds(Multimap<String, SonarIssue> fileViolations, Map<String, LinePositioner> linePositioners) {
		fileViolations.entries().removeAll { entry ->
			LinePositioner positioner = linePositioners.get(entry.key)
			return !positioner || positioner.toPosition(entry.value.line()) < 0
		}
	}

	private Map<String, LinePositioner> createLinePositioners(List<CommitFile> files) throws IOException {
		Map<String, LinePositioner> linePositioners = files.findAll {
			!it.patch != null
		}.inject([:]) { positioners, commitFile ->
			LinePositioner positioner;
			if (commitFile.status == 'added') {
				positioner = new OneToOneLinePositioner()
			} else {
				positioner = new PatchLinePositioner(commitFile.patch)
			}

			positioners << [ (commitFile.filename): positioner ]
		}

		return linePositioners;
	}


	private ComponentConverter getRelatedComponents(List<CommitFile> files) throws IOException {
		return new ComponentConverter(sonarBranch, reactorProjects, files);
	}

	private void recordGit(List<RepositoryCommit> commits, PullRequestService pullRequestService, Repository repository, Multimap<String, SonarIssue> fileViolations, Map<String, LinePositioner> linePositioners, Map<String, String> filesSha) throws IOException {
		if (commits.any()) {
			fileViolations.entries().each { entry ->
				String path = entry.getKey()
				SonarIssue issue = entry.getValue()

				String body = """
						|${issue.message()}
						|
						|
						|- Issue: ${sonarHostUrl}/issues/search#issues=${issue.key()}
						|- Rule: ${sonarHostUrl}/coding_rules#rule_key=${UrlEscapers.urlFragmentEscaper().escape(issue.ruleKey())}""".stripMargin()
				String commitId = filesSha[path]
				int position = linePositioners.get(path).toPosition(issue.line())

				log.debug("Path: {}, line: {}, position: {}", path, issue.line(), position);
				CommitComment comment = new CommitComment()
				comment.body = body
				comment.commitId = commitId
				comment.path = path
				comment.position = position
				comment.setLine(issue.line())
				try {
					pullRequestService.createComment(repository, pullRequestId, comment);
				} catch (IOException e) {
					log.error("Unable to comment on: {}", path, e);
				}
			}
		}
	}

	private void removeIssuesAlreadyReported(List<CommitComment> comments, Multimap<String,SonarIssue> fileViolations, Map<String, LinePositioner> linePositioners) throws MojoExecutionException {
		fileViolations.entries().removeAll { entry ->
			String path = entry.key
			SonarIssue issue = entry.value
			int position = linePositioners.get(path).toPosition(issue.line());

			comments.any { comment ->
				String body = comment.body
				String commentPath = comment.path

				comment.position && commentPath == path && (body.contains(issue.key()) || (comment.position == position && body.contains(issue.message())))
			}
		}
	}

	private List<SonarIssue> getIssues(ComponentConverter resources) {
		SonarIssues sonarIssues = new IssueJsonParser().parseIssues(new File(sonarReportPath).text)

		resources.getComponents().collect { component ->
			log.debug('Component: {}', component);

			sonarIssues.list().grep { SonarIssue issue ->
				log.debug('Issue {}, component key {}, component {}, status {}, result {}', issue.key(), issue.componentKey(), component, issue.status(), issue.componentKey() == component && issue.status() in [ "OPEN", "CONFIRMED", "REOPENED" ])
				issue.componentKey() == component && issue.status() in [ 'OPEN', 'CONFIRMED', 'REOPENED' ]
			}
		}.inject([]) { acc, val ->
			acc + val
		}
	}
}
