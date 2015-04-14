import javax.json.JsonObject

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject

import com.appdirect.sonar.githubintegration.ComponentConverter
import com.jcabi.github.*
import groovy.util.logging.Slf4j

@Slf4j
@Mojo(name = 'publish', aggregator = true)
class SonarPullRequestMojo extends AbstractMojo {
	/**
	 * Maven project info.
	 */
	@Parameter(required = true, readonly = true, defaultValue = '${project}')
	MavenProject project;

	/**
	 * The projects in the reactor.
	 */
	@Parameter(readonly = true, defaultValue = '${reactorProjects}')
	List<MavenProject> reactorProjects;

	/**
	 * Sonar Base URL.
	 */
	@Parameter(property = 'sonar.host.url', defaultValue = 'http://localhost:9000/')
	String sonarHostUrl;

	/**
	 * Username to access WS API.
	 */
	@Parameter(property = 'sonar.ws.username')
	String username;

	/**
	 * Password to access WS API.
	 */
	@Parameter(property = 'sonar.ws.password')
	String password;

	/**
	 * Set OAuth2 token
	 */
	@Parameter(property = 'github.oauth2', required = true)
	String oauth2;

	/**
	 * Github pull request ID
	 */
	@Parameter(property = 'github.pullRequestId', required = true)
	int pullRequestId;

	/**
	 * Github repository owner
	 */
	@Parameter(property = 'github.repositoryOwner', required = true)
	String repositoryOwner;

	/**
	 * Github repository name
	 */
	@Parameter(property = 'github.repositoryName', required = true)
	String repositoryName;

	/**
	 * Branch to be used.
	 */
	@Parameter(property = "sonar.branch")
	String sonarBranch;

	public void execute() throws MojoExecutionException {
		Github github = new RtGithub(this.oauth2)
		Repo repo = github.repos().get(new Coordinates.Simple(this.repositoryOwner, this.repositoryName))

		Pull pull = repo.pulls().get(pullRequestId)
		ComponentConverter componentConverter = getRelatedComponents(pull)
	}

	private ComponentConverter getRelatedComponents(Pull pull) throws IOException {
		Iterable<JsonObject> files = pull.files()

		return new ComponentConverter(sonarBranch, reactorProjects, files);
	}
}
