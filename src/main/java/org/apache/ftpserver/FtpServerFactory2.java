package org.apache.ftpserver;

import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ftplet.FileSystemView2;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.FtpServer;

import java.util.List;

public class FtpServerFactory2 extends FtpServerFactory {

    public FtpServerFactory2(String host, int port) {
        super();
        bind(host, port);
    }

    public void addUser(String username, Authority auth) throws FtpException {
        super.addUser(u -> {
            u.setName(username);
            u.setHomeDirectory("/");
            u.setAuthorities(auth);
        });
    }

    public FtpServer start() throws FtpException {
        FtpServer s = createServer();
        s.start();
        System.out.println("FTP server running at " + host + ":" + port);
        return s;
    }
    
}
