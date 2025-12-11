package com.g2.game.gameFactory;

import com.g2.game.gameFactory.params.GameParams;
import com.g2.game.gameFactory.params.ScalataParams;
import com.g2.game.gameMode.GameLogic;
import com.g2.game.gameMode.ScalataGame;
import com.g2.interfaces.ServiceManager;
import org.springframework.stereotype.Component;

@Component("Scalata")
public class ScalataFactory implements GameFactoryFunction {

// In ScalataFactory.java

    @Override
    public GameLogic create(ServiceManager serviceManager, GameParams params) {
        if (!(params instanceof ScalataParams))
            throw new IllegalArgumentException("Impossibile creare Scalata, params non è del tipo atteso");

        ScalataParams scalataParams = (ScalataParams) params;

        return new ScalataGame(
                serviceManager,
                params.getPlayerId(),
                params.getClassUTName(),
                params.getOpponentType(),
                params.getOpponentDifficulty(),
                params.getGameMode(),
                params.getTestClassCode(),
                scalataParams.getRemainingTime(),
                scalataParams.getScalataName(),
                scalataParams.getCurrentLevel(),
                scalataParams.getTotalLevels(),
                scalataParams.getTimeMaxPerLevel() // Assicurati che questo valore arrivi popolato dai params
        );
    }
}
