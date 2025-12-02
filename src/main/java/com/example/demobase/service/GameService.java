package com.example.demobase.service;

import com.example.demobase.dto.GameDTO;
import com.example.demobase.dto.GameResponseDTO;
import com.example.demobase.model.Game;
import com.example.demobase.model.GameInProgress;
import com.example.demobase.model.Player;
import com.example.demobase.model.Word;
import com.example.demobase.repository.GameInProgressRepository;
import com.example.demobase.repository.GameRepository;
import com.example.demobase.repository.PlayerRepository;
import com.example.demobase.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameService {
    
    private final GameRepository gameRepository;
    private final GameInProgressRepository gameInProgressRepository;
    private final PlayerRepository playerRepository;
    private final WordRepository wordRepository;
    
    private static final int MAX_INTENTOS = 7;
    private static final int PUNTOS_PALABRA_COMPLETA = 20;
    private static final int PUNTOS_POR_LETRA = 1;
    
    @Transactional
    public GameResponseDTO startGame(Long playerId) {
        Optional<Player> playerOptional = playerRepository.findById(playerId);
        if (!playerOptional.isPresent()) {
            throw new RuntimeException("El jugador con ese id no se encontro");
        }
        Player player = playerOptional.get();
        
        Optional<Word> wordOptional = wordRepository.findRandomWord();
        if (!wordOptional.isPresent()) {
            throw new RuntimeException("Todas las palabras fueron usadas");
        }
        Word word = wordOptional.get();
        
        Optional<GameInProgress> existingGameOptional = gameInProgressRepository.findByJugadorAndPalabra(playerId, word.getId());
        if (existingGameOptional.isPresent()) {
            GameInProgress existingGame = existingGameOptional.get();
            return buildResponseFromGameInProgress(existingGame);
        }
        
        word.setUtilizada(true);
        wordRepository.save(word);
        
        GameInProgress gameInProgress = new GameInProgress();
        gameInProgress.setJugador(player);
        gameInProgress.setPalabra(word);
        gameInProgress.setLetrasIntentadas("");
        gameInProgress.setIntentosRestantes(MAX_INTENTOS);
        gameInProgress.setFechaInicio(LocalDateTime.now());
        
        gameInProgress = gameInProgressRepository.save(gameInProgress);
        
        return buildResponseFromGameInProgress(gameInProgress);
    }
    
    @Transactional
    public GameResponseDTO makeGuess(Long playerId, Character letra) {
        Optional<Player> playerOptional = playerRepository.findById(playerId);
        if (!playerOptional.isPresent()) {
            throw new RuntimeException("No se encontro un jugador con ese id");
        }
        Player player = playerOptional.get();

        Character letraMayuscula = Character.toUpperCase(letra);
        
        List<GameInProgress> partidasEnCurso = gameInProgressRepository.findByJugadorIdOrderByFechaInicioDesc(playerId);
        
        if (partidasEnCurso.isEmpty()) {
            throw new RuntimeException("El jugador no tiene una partida en curso");
        }
        
        GameInProgress gameInProgress = partidasEnCurso.get(0);
        
        Word word = gameInProgress.getPalabra();
        String palabra = word.getPalabra().toUpperCase();
        String letrasIntentadasString = gameInProgress.getLetrasIntentadas();
        Set<Character> letrasIntentadas = stringToCharSet(letrasIntentadasString);
        
        if (letrasIntentadas.contains(letraMayuscula)) {
            return buildResponseFromGameInProgress(gameInProgress);
        }
        
        letrasIntentadas.add(letraMayuscula);
        
        boolean letraCorrecta = false;
        if (palabra.indexOf(letraMayuscula) >= 0) {
            letraCorrecta = true;
        }
        
        if (!letraCorrecta) {
            int intentosActuales = gameInProgress.getIntentosRestantes();
            gameInProgress.setIntentosRestantes(intentosActuales - 1);
        }

        String letrasIntentadasNuevoString = charSetToString(letrasIntentadas);
        gameInProgress.setLetrasIntentadas(letrasIntentadasNuevoString);
        
        gameInProgress = gameInProgressRepository.save(gameInProgress);
        
        GameResponseDTO response = buildResponseFromGameInProgress(gameInProgress);
        
        boolean palabraCompleta = response.getPalabraCompleta();
        int intentosRestantes = response.getIntentosRestantes();
        boolean juegoTerminado = false;
        
        if (palabraCompleta) {
            juegoTerminado = true;
        }
        if (intentosRestantes == 0) {
            juegoTerminado = true;
        }
        
        if (juegoTerminado) {
            int puntaje = response.getPuntajeAcumulado();
            boolean ganado = response.getPalabraCompleta();
            saveGame(player, word, ganado, puntaje);
            gameInProgressRepository.delete(gameInProgress);
        }
        
        return response;
    }
    
    private GameResponseDTO buildResponseFromGameInProgress(GameInProgress gameInProgress) {
        String palabra = gameInProgress.getPalabra().getPalabra().toUpperCase();
        Set<Character> letrasIntentadas = stringToCharSet(gameInProgress.getLetrasIntentadas());
        String palabraOculta = generateHiddenWord(palabra, letrasIntentadas);
        boolean palabraCompleta = palabraOculta.equals(palabra);
        
        GameResponseDTO response = new GameResponseDTO();
        response.setPalabraOculta(palabraOculta);
        response.setLetrasIntentadas(new ArrayList<>(letrasIntentadas));
        response.setIntentosRestantes(gameInProgress.getIntentosRestantes());
        response.setPalabraCompleta(palabraCompleta);
        
        int puntaje = calculateScore(palabra, letrasIntentadas, palabraCompleta, gameInProgress.getIntentosRestantes());
        response.setPuntajeAcumulado(puntaje);
        
        return response;
    }
    
    private Set<Character> stringToCharSet(String str) {
        Set<Character> set = new HashSet<>();
        if (str != null && !str.isEmpty()) {
            String[] chars = str.split(",");
            for (String c : chars) {
                if (!c.trim().isEmpty()) {
                    set.add(c.trim().charAt(0));
                }
            }
        }
        return set;
    }
    
    private String charSetToString(Set<Character> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        return set.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
    
    private int calculateScore(String palabra, Set<Character> letrasIntentadas, boolean palabraCompleta, int intentosRestantes) {
        if (palabraCompleta) {
            return PUNTOS_PALABRA_COMPLETA;
        } else if (intentosRestantes == 0) {
            // Contar letras correctas encontradas
            long letrasCorrectas = letrasIntentadas.stream()
                    .filter(letra -> palabra.indexOf(letra) >= 0)
                    .count();
            return (int) (letrasCorrectas * PUNTOS_POR_LETRA);
        }
        return 0;
    }
    
    private String generateHiddenWord(String palabra, Set<Character> letrasIntentadas) {
        StringBuilder hidden = new StringBuilder();
        for (char c : palabra.toCharArray()) {
            if (letrasIntentadas.contains(c) || c == ' ') {
                hidden.append(c);
            } else {
                hidden.append('_');
            }
        }
        return hidden.toString();
    }
    
    @Transactional
    private void saveGame(Player player, Word word, boolean ganado, int puntaje) {
        // Asegurar que la palabra esté marcada como utilizada
        if (!word.getUtilizada()) {
            word.setUtilizada(true);
            wordRepository.save(word);
        }
        
        Game game = new Game();
        game.setJugador(player);
        game.setPalabra(word);
        game.setResultado(ganado ? "GANADO" : "PERDIDO");
        game.setPuntaje(puntaje);
        game.setFechaPartida(LocalDateTime.now());
        gameRepository.save(game);
    }
    
    public List<GameDTO> getGamesByPlayer(Long playerId) {
        return gameRepository.findByJugadorId(playerId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    public List<GameDTO> getAllGames() {
        return gameRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    private GameDTO toDTO(Game game) {
        GameDTO dto = new GameDTO();
        dto.setId(game.getId());
        dto.setIdJugador(game.getJugador().getId());
        dto.setNombreJugador(game.getJugador().getNombre());
        dto.setResultado(game.getResultado());
        dto.setPuntaje(game.getPuntaje());
        dto.setFechaPartida(game.getFechaPartida());
        dto.setPalabra(game.getPalabra() != null ? game.getPalabra().getPalabra() : null);
        return dto;
    }
}

