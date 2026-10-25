package ai.orbit.agent;

interface IOrbitShizukuService {
    String execute(String command, String workingDirectory, int timeoutMs);
}
