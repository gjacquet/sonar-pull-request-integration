import javax.json.JsonObject

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.sonar.wsclient.issue.Issue as SonarIssue
import org.sonar.wsclient.issue.Issues as SonarIssues
import org.sonar.wsclient.issue.internal.IssueJsonParser

import com.appdirect.sonar.githubintegration.*
import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import com.google.common.net.UrlEscapers
import com.jcabi.github.*
import com.jcabi.github.mock.MkGithub
import groovy.util.logging.Slf4j

@Slf4j
@Mojo(name = 'publish', aggregator = true)
class SonarPullRequestMojo extends AbstractMojo {
	static {
		Multimap.withTraits(GroovyMultimap)
	}

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

	@Parameter(property = 'sonar.report.path', defaultValue = '${project.build.directory}/sonar/sonar-report.json')
	String sonarReportPath

	/**
	 * Set OAuth2 token
	 */
	@Parameter(property = 'github.mock', defaultValue = 'false')
	boolean mockGithub

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
		Github github = this.createGithub()
		Repo repo = github.repos().get(new Coordinates.Simple(this.repositoryOwner, this.repositoryName))

		Pull pull = repo.pulls().get(pullRequestId)
		ComponentConverter componentConverter = getRelatedComponents(pull)

		log.info('{} files affected', componentConverter.size())

		List<SonarIssue> issues = getIssues(componentConverter);
		log.info( "Found {} issues", issues.size());

		Multimap<String, SonarIssue> fileViolations = issues.inject(LinkedHashMultimap.create()) { violations, issue ->
			String path = componentConverter.componentToPath(issue.componentKey())
			violations << [ (path): issue ]
		}

		Map<String, LinePositioner> linePositioners;
		try {
			linePositioners = createLinePositioners(pull);
		} catch (IOException e) {
			throw new MojoExecutionException('Unable to get commits on github', e );
		}

		Map<String, String> filesSha = getFilesSha(pull)

		removeIssuesOutsideBounds(fileViolations, linePositioners);
		log.info('Found {} files with issues ({} issues) ', fileViolations.keySet().size(), fileViolations.size())

		removeIssuesAlreadyReported(pull, fileViolations, linePositioners)
		log.info('Files with new issues: {} ({} issues)', fileViolations.keySet().size(), fileViolations.size())

		//recordGit(pull, fileViolations, linePositioners, filesSha)
	}

	private Github createGithub() {
		if (mockGithub) {
			MkGithub mock = new MkGithub()
		} else {
			new RtGithub(this.oauth2)
		}
	}

	private Map<String, String> getFilesSha(Pull pull) {
		pull.files().inject([:]) { map, commitFile ->
			map << [ (commitFile.getString('filename')): commitFile.getString('blob_url').replaceAll( ".*blob/", "" ).replaceAll( "/.*", "" ) ]
		}
	}

	private void removeIssuesOutsideBounds(Multimap<String, SonarIssue> fileViolations, Map<String, LinePositioner> linePositioners) {
		fileViolations.entries().removeAll { entry ->
			LinePositioner positioner = linePositioners.get(entry.key)
			return !positioner || positioner.toPosition(entry.value.line()) < 0
		}
	}

	private Map<String, LinePositioner> createLinePositioners(Pull pull) throws IOException {
		Map<String, LinePositioner> linePositioners = pull.files().findAll {
			!it.isNull('patch')
		}.inject([:]) { positioners, commitFile ->
			LinePositioner positioner;
			if (commitFile.getString('status') == 'added') {
				positioner = new OneToOneLinePositioner()
			} else {
				positioner = new PatchLinePositioner(commitFile.getString('patch'))
			}

			positioners << [ (commitFile.getString('filename')): positioner ]
		}

		return linePositioners;
	}


	private ComponentConverter getRelatedComponents(Pull pull) throws IOException {
		Iterable<JsonObject> files = pull.files()

		return new ComponentConverter(sonarBranch, reactorProjects, files);
	}

	private void recordGit(Pull pull, Multimap<String, SonarIssue> fileViolations, Map<String, LinePositioner> linePositioners, Map<String, String> filesSha) throws IOException {
		if (pull.commits().any()) {
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
				try {
					pull.comments().post(body, commitId, path, position)
				} catch (IOException e) {
					log.error("Unable to comment on: {}", path, e);
				}
			}
		}
	}

	private void removeIssuesAlreadyReported(Pull pull, Multimap<String,SonarIssue> fileViolations,
											 Map<String, LinePositioner> linePositioners) throws MojoExecutionException {
		List<PullComment> comments = pull.comments().iterate(pull.number(), [:]).collect()
		fileViolations.entries().removeAll { entry ->
			String path = entry.key
			SonarIssue issue = entry.value
			int position = linePositioners.get(path).toPosition(issue.line());

			comments.any { comment ->
				String body = comment.json().getString('body')
				int commentPosition = comment.json().getInt('position')
				String commentPath = comment.json().getString('path')

				commentPath == path && (body.contains(issue.key()) || (commentPosition == position && body.contains(issue.message())))
			}
		}
	}

	private List<SonarIssue> getIssues(ComponentConverter resources) {
		SonarIssues sonarIssues = new IssueJsonParser().parseIssues(new File(sonarReportPath).text)

		resources.getComponents().collect { component ->
			log.debug("Component: {}", component);

			sonarIssues.list().grep { issue ->
				issue.componentKey() == component && issue.status() in [ "OPEN", "CONFIRMED", "REOPENED" ]
			}
		}.inject([]) { acc, val ->
			acc + val
		}
	}
}
