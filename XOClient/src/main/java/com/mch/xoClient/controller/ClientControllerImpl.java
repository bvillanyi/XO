package com.mch.xoClient.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.mch.xoClient.communication.NetworkDataSource;
import com.mch.xoClient.view.ClientView;
import com.mch.xoData.exception.XOException;
import com.mch.xoData.transferData.TransferData;
import com.mch.xoData.transferData.TransferType;

@Component
public class ClientControllerImpl implements ClientController {

  public static final char X = 'X';
  public static final char O = 'O';
  
  @Autowired
  private NetworkDataSource network;

  @Autowired
  private ClientView view;

  private String userName;
  private String enemyName;
  private char mark;

  // start the dialog
  @SuppressWarnings("unchecked")
  public boolean start(String userName, String host, int port) {
    this.userName = userName;
    // try to connect to the server
    try {
      network.connect(host, port);
    }
    catch (IOException ec) {
      updateView("Error connectiong to server: " + ec);
      return false;
    }
    String msg = "Connection accepted " + network.getInetAddress() + ":" + network.getPort();
    updateView(msg);

    // Send user name to the server this is the only message that we
    // will send as a String. All other messages will be TransferData objects
    try {
      network.send(userName);
    } catch (IOException eIO) {
      updateView("Exception doing login : " + eIO);
      try {
        network.disconnect();
      } catch (IOException e) {
        updateView(e.getMessage());
      }
      // inform the GUI
      view.connectionFailed();
      return false;
    }

    // wait the server response if the user name is not taken, block the
    // client GUI
    TransferData<Boolean> cm = null;
    try {
      cm = (TransferData<Boolean>) network.receive();
    } catch (ClassNotFoundException | IOException e) {
      updateView(e.getMessage());
      return false;
    }
    if (cm.getType() != TransferType.LOGIN || cm.getMessage() != true) {
      updateView("User name taken.");
      return false;
    }

    // creates the Thread to listen from the server
    new ListenFromServer().start();

    // success we inform the caller that it worked
    return true;
  }

  public boolean mark(int location) {
    TransferData<Integer> msg = new TransferData<>(TransferType.MARK, location, userName, enemyName);
    return sendMessage(msg);
  }

  public boolean invite(String to) {
    TransferData<?> msg = new TransferData<String>(TransferType.INVITE, null, userName, to);
    return sendMessage(msg);
  }

  public void doGetUserList() {
    sendMessage(new TransferData<String>(TransferType.WHOISIN, "", null, null));
  }

  public void doLogout() {
    sendMessage(new TransferData<String>(TransferType.LOGOUT, "", null, null));
  }

  // solve this with Observer? ..
  private void updateView(String msg) {
    view.log(msg);
  }

  // send a message to the server
  private boolean sendMessage(TransferData<?> msg) {
    try {
      network.send(msg);
      return true;
    } catch (IOException e) {
      updateView("Exception writing to server: " + e);
      return false;
    }
  }

  @Override
  public void saveGame() {
    sendMessage(new TransferData<String>(TransferType.SAVE, "", null, null));
  }

  @Override
  public void loadGame() {
    sendMessage(new TransferData<String>(TransferType.LOAD, "", null, null));
  }

  public char getMark() {
    return mark;
  }

  // a class that waits for the message from the server
  private class ListenFromServer extends Thread {

    @SuppressWarnings("unchecked")
    public void run() {
      while (true) {
        try {
          // all results are received here
          Object o = network.receive();
          if (o instanceof TransferData<?>) {
            TransferData<?> cm = (TransferData<?>) o;

            TransferType transType = cm.getType();
            // Switch on the type of message receive
            switch (transType) {
            case WHOISIN:
              view.setUsers((List<String>) cm.getMessage());
              break;
            case INVITE:
              enemyName = (String) cm.getFrom();
              mark = (Character) cm.getMessage();
              view.notifyInvited(mark, enemyName);
              break;
            case LOAD:
              char[][] table = (char[][]) cm.getMessage();
              if (table != null) {
                view.log("Game loaded.");
                if (userName.equals(cm.getFrom()))
                  mark = X;
                else if (userName.equals(cm.getTo()))
                  mark = O;
                int xCount = 0;
                int yCount = 0;
                for (char[] cv : table)
                  for (char c : cv)
                    if (c == X)
                      xCount++;
                    else if (c == O)
                      yCount++;
                if (xCount == yCount) {
                  if (mark == X) {
                    view.updateWholeTable(table, true, mark);
                  } else {
                    view.updateWholeTable(table, false, mark);
                  }
                } else {
                  if (mark == O) {
                    view.updateWholeTable(table, true, mark);
                  } else {
                    view.updateWholeTable(table, false, mark);
                  }
                }
              } else {
                view.log("Loading failed!");
              }
              break;
            case WIN:
              view.notifyGameEnded((Boolean) cm.getMessage());
              break;
            case MARK:
              if (mark == X)
                view.putMark((Integer) cm.getMessage(), O);
              else
                view.putMark((Integer) cm.getMessage(), X);
              break;
            default:
              view.showMessage("Unknown data type received!");
            }
          } else
            throw new XOException("Did not receive TransferData object from stream.");
        } catch (IOException e) {
          updateView("Server has close the connection: " + e);
          if (view != null)
            view.connectionFailed(); // ..
          // break the while loop, finish listening
          break;
        } catch (XOException e2) {
          updateView(e2.getMessage());
        } catch (ClassNotFoundException e3) {
          updateView("Error reading from stream in client: " + e3.getMessage());
        }
      }
    }
  }
}
