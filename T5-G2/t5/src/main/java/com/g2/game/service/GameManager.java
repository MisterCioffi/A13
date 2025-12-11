/*
 *   Copyright (c) 2025 Stefano Marano https://github.com/StefanoMarano80017
 *   All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.g2.game.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g2.game.gameDTO.EndGameDTO.EndGameResponseDTO;
import com.g2.game.gameDTO.EndGameDTO.EndScalataGameResponseDTO;
import com.g2.game.gameDTO.RunGameDTO.RunGameRequestDTO;
import com.g2.game.gameDTO.RunGameDTO.RunGameResponseDTO;
import com.g2.game.gameDTO.StartGameDTO.StartGameRequestDTO;
import com.g2.game.gameDTO.StartGameDTO.StartGameResponseDTO;
import com.g2.game.gameFactory.params.GameParams;
import com.g2.game.gameFactory.params.GameParamsFactory;
import com.g2.game.gameMode.Compile.CompileResult;
import com.g2.game.gameMode.GameLogic;
import com.g2.game.gameMode.ScalataGame;
import com.g2.interfaces.ServiceManager;
import com.g2.model.configuration.GameExecutionConfig;
import com.g2.model.dto.GameProgressDTO;
import com.g2.session.SessionService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import testrobotchallenge.commons.models.dto.score.EvosuiteCoverageDTO;
import testrobotchallenge.commons.models.dto.score.JacocoCoverageDTO;
import testrobotchallenge.commons.models.opponent.GameMode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GameManager {

    private static final String TEST_CLASS_NAME_PREFIX = "Test";
    private static final Logger logger = LoggerFactory.getLogger(GameManager.class);

    private final GameService gameService;
    private final SessionService sessionService;
    private final PlayerStatService playerStatService;
    private final LogWriterService logWriterService;

    @Value("${config.turn.file}")
    private String turnExecutionConfigFile;

    private GameExecutionConfig config;

    public GameManager(GameService gameService, SessionService sessionService,
                       PlayerStatService playerStatService, LogWriterService logWriterService) {
        this.gameService = gameService;
        this.sessionService = sessionService;
        this.playerStatService = playerStatService;
        this.logWriterService = logWriterService;
    }

    @PostConstruct
    public void init() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            File file = new File("%s/%s".formatted(System.getProperty("user.dir"),
                    turnExecutionConfigFile.replace("/", File.separator)));
            this.config = objectMapper.readValue(file, GameExecutionConfig.class);
        } catch (IOException e) {
            logger.info("[PostConstruct init] Error in loading gamification_config.json, using default values: {}", e.getMessage());
            this.config = new GameExecutionConfig(true);
        }
    }

    public StartGameResponseDTO handleStartNewGame(StartGameRequestDTO requestDTO) {
        GameParams gameParams = GameParamsFactory.generateCreateParams(requestDTO);
        GameLogic gameLogic = gameService.createNewGame(gameParams);

        gameLogic.startGame();
        gameLogic.startRound();

        logger.info("[START_GAME] Partita creata con successo. GameID={}, mode={}",
                gameLogic.getGameID(), gameLogic.getGameMode());

        sessionService.setGameMode(gameParams.getPlayerId(), gameLogic);

        GameProgressDTO progress = gameService.createNewGameProgress(gameParams);

        return new StartGameResponseDTO(gameLogic.getGameID(), "created");
    }

    public RunGameResponseDTO handlePlayTurn(RunGameRequestDTO dto, boolean isGameEnd) {
        GameParams updateParams = GameParamsFactory.generateUpdateParams(dto);

        Long playerId = updateParams.getPlayerId();
        GameMode gameMode = updateParams.getGameMode();

        GameLogic currentGame = handleGetCurrentGame(playerId, gameMode);

        String classUTCode = updateParams.getClassUTCode().isEmpty() ?
                currentGame.getClassUTCode() :
                updateParams.getClassUTCode();

        String testClassCode = updateParams.getTestClassCode().isEmpty() ?
                currentGame.getTestingClassCode() :
                updateParams.getTestClassCode();

        String classUTName = currentGame.getClassUTName();
        String testClassName = TEST_CLASS_NAME_PREFIX + classUTName;

        String classUTFileName = classUTName + ".java";
        String testClassFileName = testClassName + ".java";

        Pair<JacocoCoverageDTO, EvosuiteCoverageDTO> coverage = handleCompileAndCoverage(
                classUTName, classUTFileName, classUTCode,
                testClassName, testClassFileName, testClassCode, isGameEnd);

        CompileResult playerCoverageResult =
                new CompileResult(coverage.getFirst(), coverage.getSecond());

        CompileResult opponentCoverageResult =
                gameService.getOpponentCoverage(currentGame);

        gameService.closeTurn(playerCoverageResult, opponentCoverageResult, currentGame, updateParams);
        sessionService.updateGameMode(playerId, currentGame);

        if (playerCoverageResult.hasSuccess()) {
            int opponentScore = currentGame.getScore(opponentCoverageResult);
            int userScore = currentGame.getScore(playerCoverageResult);

            Set<String> unlockedAchievements = new HashSet<>();

            // FIX: Blocco Try-Catch per evitare che errori su T23 blocchino il gioco
            try {
                unlockedAchievements = playerStatService.unlockGameModeAchievements(
                        currentGame, playerCoverageResult, opponentCoverageResult);
            } catch (Exception e) {
                // Logghiamo l'errore ma NON blocchiamo il ritorno della risposta
                logger.error("[HANDLE_PLAY_TURN] Errore non bloccante nel calcolo degli Achievement (T23): {}", e.getMessage());
                // unlockedAchievements resterà vuoto, il gioco prosegue
            }

            return new RunGameResponseDTO(
                    playerCoverageResult,
                    opponentCoverageResult,
                    currentGame.isWinner(),
                    userScore,
                    opponentScore,
                    unlockedAchievements
            );
        } else {
            return new RunGameResponseDTO(
                    playerCoverageResult,
                    null,
                    false,
                    0,
                    0,
                    new HashSet<>()
            );
        }
    }

    public void handlePauseGame(String rawRequest) {
        RunGameRequestDTO requestDTO;
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            requestDTO = objectMapper.readValue(rawRequest, RunGameRequestDTO.class);
        } catch (JsonProcessingException e) {
            logger.error("Errore nel parsing della richiesta di aggiornamento della sessione", e);
            return;
        }

        GameParams updateParams = GameParamsFactory.generateUpdateParams(requestDTO);

        GameLogic currentGame = handleGetCurrentGame(
                requestDTO.getPlayerId(), requestDTO.getGameMode());

        gameService.pauseTurn(
                currentGame.getUserCompileResult(),
                currentGame.getRobotCompileResult(),
                currentGame,
                updateParams);

        sessionService.updateGameMode(requestDTO.getPlayerId(), currentGame);
    }

    public EndGameResponseDTO handleEndGame(RunGameRequestDTO requestDTO) {
        GameParams updateParams = GameParamsFactory.generateUpdateParams(requestDTO);

        RunGameResponseDTO runGameResponse = new RunGameResponseDTO();
        Set<String> achievementsUnlocked = new HashSet<>();

        if (!updateParams.getTestClassCode().isEmpty()
                || config.isExecuteEvosuiteOnlyAtEndGame()) {

            runGameResponse = handlePlayTurn(requestDTO, true);
            achievementsUnlocked.addAll(runGameResponse.getUnlockedAchievements());
        }

        GameLogic currentGame =
                handleGetCurrentGame(updateParams.getPlayerId(), updateParams.getGameMode());

        // ✅ FIX VP: instanceof classico
        if (currentGame instanceof ScalataGame) {

            ScalataGame scalataGame = (ScalataGame) currentGame;

            if (scalataGame.isWinner()
                    && scalataGame.getCurrentLevel() == scalataGame.getTotalLevels()) {

                handleCloseGame(currentGame, false);

            } else {
                achievementsUnlocked.addAll(handleCloseLevel(scalataGame));
            }

        } else {
            handleCloseGame(currentGame, false);
        }

        if (currentGame.getUserCompileResult() == null
                || !currentGame.getUserCompileResult().hasSuccess()) {

            return new EndGameResponseDTO(0, 0, false, 0, runGameResponse);

        } else if (!currentGame.isWinner()) {

            return new EndGameResponseDTO(
                    currentGame.getScore(currentGame.getRobotCompileResult()),
                    currentGame.getScore(currentGame.getUserCompileResult()),
                    false, 0, achievementsUnlocked, runGameResponse);

        } else {

            int expGained = 0;

            // ✅ FIX VP: instanceof classico
            if (currentGame instanceof ScalataGame) {

                ScalataGame scalataGame = (ScalataGame) currentGame;

                if (scalataGame.getCurrentLevel() <= scalataGame.getTotalLevels()) {
                    logger.info("[EndGame] Scalata: livello completato, XP già assegnati");
                } else {
                    expGained = playerStatService.assignExperiencePoints(currentGame);
                    achievementsUnlocked.addAll(
                            playerStatService.unlockGlobalAchievements(
                                    currentGame.getPlayerID()));
                }

            } else {

                expGained = playerStatService.assignExperiencePoints(currentGame);
                achievementsUnlocked.addAll(
                        playerStatService.unlockGlobalAchievements(
                                currentGame.getPlayerID()));
            }

            return new EndGameResponseDTO(
                    currentGame.getScore(currentGame.getRobotCompileResult()),
                    currentGame.getScore(currentGame.getUserCompileResult()),
                    true,
                    expGained,
                    achievementsUnlocked,
                    runGameResponse
            );
        }
    }

    public void handleSurrendGame(long playerId, GameMode gameMode) {
        GameLogic currentGame = handleGetCurrentGame(playerId, gameMode);
        handleCloseGame(currentGame, true);
    }

    private GameLogic handleGetCurrentGame(long playerId, GameMode gameMode) {
        GameLogic currentGame = sessionService.getGameMode(playerId, gameMode);
        gameService.addServiceManager(currentGame);
        return currentGame;
    }

    private Pair<JacocoCoverageDTO, EvosuiteCoverageDTO> handleCompileAndCoverage(
            String classUTName, String classUTFileName, String classUTCode,
            String testClassName, String testClassFileName, String testClassCode,
            boolean isGameEnd) {

        JacocoCoverageDTO responseT7;
        EvosuiteCoverageDTO responseT8 = new EvosuiteCoverageDTO();

        responseT7 = gameService.compilePlayerTest(
                classUTFileName, classUTCode, testClassFileName, testClassCode);

        if ((!config.isExecuteEvosuiteOnlyAtEndGame() || isGameEnd)
                && responseT7.getCoverage() != null) {

            responseT8 = gameService.computeEvosuiteCoverage(
                    classUTName, classUTCode, testClassName, testClassCode);
        }

        return Pair.of(responseT7, responseT8);
    }

    private void handleCloseGame(GameLogic currentGame, boolean isGameSurrendered) {
        gameService.closeGame(currentGame, isGameSurrendered);
        sessionService.removeGameMode(
                currentGame.getPlayerID(),
                currentGame.getGameMode(),
                java.util.Optional.empty());
    }

// In GameManager.java

// In GameManager.java

    public List<String> handleCloseLevel(ScalataGame scalataGame) {
        List<String> achievementsUnlocked = new ArrayList<>();

        // CASO 1: IL GIOCATORE HA PERSO IL LIVELLO (RETRY)
// CASO 1: IL GIOCATORE HA PERSO IL LIVELLO (RETRY)
        if (!scalataGame.isWinner()) {
            try {
                logger.info("[SCALATA] Livello {} fallito. Reset per riprova (Nuovo Round).", scalataGame.getCurrentLevel());

                // 1. Reset parametri sessione T5
                // Usa il tempo originale se disponibile, altrimenti default 300s (non 600)
                int timeToSet = scalataGame.getTimeMaxPerLevel() > 0 ? scalataGame.getTimeMaxPerLevel() : 300;

                scalataGame.setRemainingTime(timeToSet);
                scalataGame.setCurrentTurn(0);

                // 2. STRATEGIA "NUOVO ROUND" (Più sicura per evitare conflitti ID Turno)

                // a. Chiudi il round fallito su T4
                scalataGame.getServiceManager().handleRequest("T4", "EndRound", scalataGame.getGameID());

                // b. (Opzionale) Incrementa contatore se serve per statistiche,
                // ma il fatto di avere più righe "Round" per lo stesso livello già fa da storico.
                // scalataGame.getServiceManager().handleRequest("T4", "IncrementRoundAttempt", scalataGame.getGameID());

                // c. Crea un NUOVO round per il nuovo tentativo
                // Nota: startRound() usa i dati correnti (livello, classe, ecc.) che non sono cambiati.
                // IMPORTANTE: T4 creerà un nuovo round ID. T5 userà questo nuovo ID per i futuri turni.
                scalataGame.startRound();

                // 3. Salva lo stato in sessione
                sessionService.updateGameMode(scalataGame.getPlayerID(), scalataGame);

            } catch (Exception e) {
                logger.error("[SCALATA] Errore nel reset del livello fallito: {}", e.getMessage());
            }
            return achievementsUnlocked;
        }

        // CASO 2: IL GIOCATORE HA VINTO IL LIVELLO (AVANZAMENTO)
        try {
            logger.info("[SCALATA] Utente ha vinto il livello {}. Inizio procedura avanzamento.", scalataGame.getCurrentLevel());

            // A. Assegnazione XP e Achievement (Safe Mode)
            try {
                int expGained = playerStatService.assignExperiencePoints(scalataGame);
                achievementsUnlocked.addAll(playerStatService.unlockGlobalAchievements(scalataGame.getPlayerID()));
            } catch (Exception e) {
                logger.error("[SCALATA] Errore non bloccante durante assegnazione XP/Achievement (T23): {}", e.getMessage());
            }

            // B. RECUPERO DATI PROSSIMO LIVELLO DA T1
            int nextLevelIndex = scalataGame.getCurrentLevel() + 1;

            logger.info("[SCALATA] Richiedo a T1 dati per Scalata: '{}', Livello: {}", scalataGame.getScalataName(), nextLevelIndex);

            @SuppressWarnings("unchecked")
            Map<String, Object> nextLevelData = (Map<String, Object>) scalataGame.getServiceManager().handleRequest(
                    "T1", "getLevelByScalataAndPosition",
                    scalataGame.getScalataName(),
                    nextLevelIndex);

            if (nextLevelData == null || !nextLevelData.containsKey("className")) {
                throw new RuntimeException("T1 ha restituito dati incompleti per il livello " + nextLevelIndex);
            }

            String nextClassUT = (String) nextLevelData.get("className");
            int nextTimeMax = nextLevelData.get("tempoMax") != null
                    ? ((Number) nextLevelData.get("tempoMax")).intValue()
                    : 600;

            // C. ESECUZIONE TRANSIZIONE (Chiusura vecchio Round -> Apertura Nuovo Livello)

            // 1. Chiudi il round del livello appena superato e incrementa livello globale
            scalataGame.getServiceManager().handleRequest("T4", "EndRound", scalataGame.getGameID());
            scalataGame.getServiceManager().handleRequest("T4", "IncrementCurrentLevel", scalataGame.getGameID());

            // 2. Aggiorna Oggetto Sessione
            scalataGame.setCurrentLevel(nextLevelIndex);
            scalataGame.setClassUTName(nextClassUT);
            scalataGame.setClassUTCode(null); // CRUCIALE: Pulisce il codice vecchio
            scalataGame.setTimeMaxPerLevel(nextTimeMax);
            scalataGame.setRemainingTime(nextTimeMax);
            scalataGame.setCurrentTurn(0);

            // 3. Avvia il NUOVO round per il NUOVO livello su T4
            scalataGame.startRound();

            // 4. Salva la sessione
            sessionService.updateGameMode(scalataGame.getPlayerID(), scalataGame);

            logger.info("[SCALATA] Successo! Sessione aggiornata a Livello: {}, Classe: {}", nextLevelIndex, nextClassUT);

        } catch (Exception e) {
            logger.error("[SCALATA] ERRORE CRITICO AVANZAMENTO LIVELLO: ", e);
            throw new RuntimeException("Errore avanzamento livello: " + e.getMessage());
        }

        return achievementsUnlocked;
    }
}
