package studio.qsa3ed.qcore.common.model;

/**
 * The outcome of evaluating one server as a routing destination.
 *
 * @param serverId the evaluated server
 * @param eligible whether the server may receive the player
 * @param score    computed score, higher is better; {@code 0} when not eligible
 * @param reason   human readable explanation, always populated
 */
public record CandidateEvaluation(String serverId, boolean eligible, double score, String reason) {

    public static CandidateEvaluation rejected(String serverId, String reason) {
        return new CandidateEvaluation(serverId, false, 0.0, reason);
    }

    public static CandidateEvaluation accepted(String serverId, double score, String reason) {
        return new CandidateEvaluation(serverId, true, score, reason);
    }
}
