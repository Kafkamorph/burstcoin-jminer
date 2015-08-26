package burstcoin.jminer;

import burstcoin.jminer.core.CoreProperties;
import burstcoin.jminer.core.network.Network;
import burstcoin.jminer.core.network.event.NetworkDevResultConfirmedEvent;
import burstcoin.jminer.core.network.event.NetworkLastWinnerEvent;
import burstcoin.jminer.core.network.event.NetworkResultConfirmedEvent;
import burstcoin.jminer.core.network.event.NetworkResultErrorEvent;
import burstcoin.jminer.core.network.event.NetworkStateChangeEvent;
import burstcoin.jminer.core.network.model.DevPoolResult;
import burstcoin.jminer.core.reader.event.ReaderCorruptFileEvent;
import burstcoin.jminer.core.reader.event.ReaderProgressChangedEvent;
import burstcoin.jminer.core.round.Round;
import burstcoin.jminer.core.round.event.RoundFinishedEvent;
import burstcoin.jminer.core.round.event.RoundSingleResultEvent;
import burstcoin.jminer.core.round.event.RoundSingleResultSkippedEvent;
import burstcoin.jminer.core.round.event.RoundStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Timer;
import java.util.TimerTask;


@SpringBootApplication
public class CommandLineRunner
  implements org.springframework.boot.CommandLineRunner
{
  private static final Logger LOG = LoggerFactory.getLogger(CommandLineRunner.class);

  private static ConfigurableApplicationContext context;

  private static boolean roundFinished = true;
  private static long blockNumber;
  private static int progressLogStep;
  private static int numberOfProgressLogsPerBlock = CoreProperties.getReadProgressPerRound();

  public static void main(String[] args)
  {
    Timer timer = new Timer();
    timer.schedule(new TimerTask()
    {
      @Override
      public void run()
      {
        if(context != null)
        {
          if(!roundFinished)
          {
            LOG.info("waiting for round to finish ... to stop and restart mining engine.");
          }
          while(!roundFinished)
          {
            try
            {
              // even if new round was started within 1s and roundFinished is false again, it will trigger on next block
              Thread.sleep(1000);
            }
            catch(InterruptedException e)
            {
              e.printStackTrace();
            }
          }
          LOG.info("mining engine will restart now ... restartInterval: " + CoreProperties.getRestartInterval() + "min");

          Network network = context.getBean(Network.class);
          network.stopTimer();
          Round round = context.getBean(Round.class);
          round.stopTimer();

          progressLogStep = numberOfProgressLogsPerBlock;

          context.stop();
          context.close();
        }

        LOG.info("start the engines ...");
        context = SpringApplication.run(CommandLineRunner.class, args);

        context.addApplicationListener(new ApplicationListener<RoundFinishedEvent>()
        {
          @Override
          public void onApplicationEvent(RoundFinishedEvent event)
          {
            roundFinished = true;

            long s = event.getRoundTime() / 1000;
            long ms = event.getRoundTime() % 1000;

            String bestDeadline = Long.MAX_VALUE == event.getBestCommittedDeadline() ? "N/A" : String.valueOf(event.getBestCommittedDeadline());
            LOG.info("FINISH block '" + event.getBlockNumber() + "', "
                     + "best deadline '" + bestDeadline + "', "
                     + "round time '" + s + "s " + ms + "ms'");
          }
        });

        context.addApplicationListener(new ApplicationListener<NetworkLastWinnerEvent>()
        {
          @Override
          public void onApplicationEvent(NetworkLastWinnerEvent event)
          {
            if(blockNumber - 1 == event.getLastBlockNumber())
            {
              LOG.info("      winner block '" + event.getLastBlockNumber() + "', '" + event.getWinner() + "'");
            }
            else
            {
              LOG.error("Error: NetworkLastWinnerEvent for block: " + event.getLastBlockNumber() + " is outdated!");
            }
          }
        });

        context.addApplicationListener(new ApplicationListener<NetworkStateChangeEvent>()
        {
          @Override
          public void onApplicationEvent(NetworkStateChangeEvent event)
          {
            blockNumber = event.getBlockNumber();
          }
        });

        context.addApplicationListener(new ApplicationListener<RoundStartedEvent>()
        {
          @Override
          public void onApplicationEvent(RoundStartedEvent event)
          {
            roundFinished = false;
            progressLogStep = numberOfProgressLogsPerBlock;

            LOG.info("-------------------------------------------------------");
            LOG.info("START block '" + event.getBlockNumber() + "', "
                     + "scoopNumber '" + event.getScoopNumber() + "', "
                     + "capacity '" + event.getCapacity() / 1024 / 1024 / 1024 + " GB'"
                    );
            String target = event.getTargetDeadline() == Long.MAX_VALUE ? "N/A" : String.valueOf(event.getTargetDeadline());
            LOG.info("      targetDeadline '" + target + "', " + "baseTarget '" + String.valueOf(event.getBaseTarget()) + "'");
          }
        });

        context.addApplicationListener(new ApplicationListener<ReaderProgressChangedEvent>()
        {
          @Override
          public void onApplicationEvent(ReaderProgressChangedEvent event)
          {
            long logStepCapacity = event.getCapacity() / numberOfProgressLogsPerBlock;

            if(event.getRemainingCapacity() < logStepCapacity * progressLogStep || event.getRemainingCapacity() == 0)
            {
              progressLogStep--;

              BigDecimal totalCapacity = new BigDecimal(event.getCapacity());
              BigDecimal factor = BigDecimal.ONE.divide(totalCapacity, MathContext.DECIMAL32);
              BigDecimal progress = factor.multiply(new BigDecimal(event.getCapacity() - event.getRemainingCapacity()));
              int percentage = (int) Math.ceil(progress.doubleValue() * 100);
              percentage = percentage > 100 ? 100 : percentage;

              // calculate capacity
              long doneBytes = event.getCapacity() - event.getRemainingCapacity();
              long doneTB = doneBytes / 1024 / 1024 / 1024 / 1024;
              long doneGB = doneBytes / 1024 / 1024 / 1024 % 1024;

              // calculate reading speed
              long effectiveBytesPerMs = (doneBytes / 4096) / event.getElapsedTime();
              long effectiveMBPerSec = (effectiveBytesPerMs * 1000) / 1024 / 1024;

              LOG.info(String.valueOf(percentage) + "% done (" + doneTB + "TB " + doneGB + "GB), eff.read '" + effectiveMBPerSec + " MB/s'");
            }
          }
        });

        context.addApplicationListener(new ApplicationListener<RoundSingleResultEvent>()
        {
          @Override
          public void onApplicationEvent(RoundSingleResultEvent event)
          {
            LOG.info("dl '" + event.getCalculatedDeadline() + "' send (" + (event.isPoolMining() ? "pool" : "solo") + ")");
          }
        });

        context.addApplicationListener(new ApplicationListener<RoundSingleResultSkippedEvent>()
        {
          @Override
          public void onApplicationEvent(RoundSingleResultSkippedEvent event)
          {
            LOG.info("dl '" + event.getCalculatedDeadline() + "' > '" + event.getTargetDeadline() + "' skipped");
          }
        });

        context.addApplicationListener(new ApplicationListener<NetworkResultConfirmedEvent>()
        {
          @Override
          public void onApplicationEvent(NetworkResultConfirmedEvent event)
          {
            LOG.info("dl '" + event.getDeadline() + "' confirmed!  [ " + getDeadlineTime(event.getDeadline()) + " ]");
          }
        });

        context.addApplicationListener(new ApplicationListener<NetworkDevResultConfirmedEvent>()
        {
          @Override
          public void onApplicationEvent(NetworkDevResultConfirmedEvent event)
          {
            LOG.info("devPool response '" + event.getResponse() + "', block '" + event.getBlockNumber() + "'");
            for(DevPoolResult devPoolResult : event.getDevPoolResults())
            {
              LOG.info(
                "dl '" + devPoolResult.getCalculatedDeadline() + "' successful committed!  [ " + getDeadlineTime(devPoolResult.getCalculatedDeadline()) + " ]");
            }
          }
        });

        context.addApplicationListener(new ApplicationListener<NetworkResultErrorEvent>()
        {
          @Override
          public void onApplicationEvent(NetworkResultErrorEvent event)
          {
            LOG.info("strange dl result '" + event.getStrangeDeadline() + "', calculated '" + event.getCalculatedDeadline() + "' "
                     + "block '" + event.getBlockNumber() + "' nonce '" + event.getNonce() + "'");
          }
        });

        context.addApplicationListener(new ApplicationListener<ReaderCorruptFileEvent>()
        {
          @Override
          public void onApplicationEvent(ReaderCorruptFileEvent event)
          {
            LOG.info("strange dl source '" + event.getFilePath() + "' (try replotting!?)");
            LOG.info("strange dl file chunks '" + event.getNumberOfChunks() + "', "
                     + "parts per chunk '" + event.getNumberOfParts() + "', "
                     + "block '" + event.getBlockNumber() + "'");
          }
        });

        LOG.info("");
//        LOG.info(":::::::::  :::    ::: :::::::::   ::::::::  ::::::::::::");
//        LOG.info(":+:    :+: :+:    :+: :+:    :+: :+:            :+:");
//        LOG.info("+#++:++#+  +#+    +:+ +#++:++#:  +#++:++#++     +#+");
//        LOG.info("+#+    +#+ +#+    +#+ +#+    +#+        +#+     +#+");
//        LOG.info("#+#    #+# #+#    #+# #+#    #+#         #+#    #+#");
//        LOG.info("#########   ########  ###    ###  BURSTCOIN     ###");
//        LOG.info("::::::::::::::::::::::::::::::::::::::::::::::::::::::::");
        LOG.info("                            Burstcoin (BURST)");
        LOG.info("            __         __   GPU assisted PoC-Miner");
        LOG.info("           |__| _____ |__| ____   ___________ ");
        LOG.info("   version |  |/     \\|  |/    \\_/ __ \\_  __ \\");
        LOG.info("     0.3.6 |  |  Y Y  \\  |   |  \\  ___/|  | \\/");
        LOG.info("       /\\__|  |__|_|  /__|___|  /\\___  >__| ");
        LOG.info("       \\______|     \\/        \\/     \\/");
        LOG.info("      mining engine: BURST-LUXE-ZDVD-CX3E-3SM58");
        LOG.info("     openCL checker: BURST-QHCJ-9HB5-PTGC-5Q8J9");

        Network network = context.getBean(Network.class);
        network.startMining();
      }
    }, 0, 1000 * 60 * CoreProperties.getRestartInterval());
  }

  private static String getDeadlineTime(Long calculatedDeadline)
  {
    long sec = calculatedDeadline;
    long min = sec / 60;
    sec = sec % 60;
    long hours = min / 60;
    min = min % 60;
    long days = hours / 24;
    hours = hours % 24;
    return days + "d " + hours + "h " + min + "m " + sec + "s";
  }

  @Override
  public void run(String... args)
    throws Exception
  {
  }
}
