package top.everblue.oj.judge.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Request payload for a solution submission. The actor always comes from JWT. */
@Data
public class SubmitSolutionCommand {

    @NotNull
    private Long problemId;

    private Long contestId;

    @NotBlank
    private String language;

    @NotBlank
    private String code;
}
