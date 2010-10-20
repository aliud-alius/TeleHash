package org.telehash;

import static org.telehash.TelexBuilder.formatAddress;
import static org.telehash.TelexBuilder.parseAddress;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.SimpleBufferAllocator;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.DatagramConnector;
import org.apache.mina.transport.socket.nio.NioDatagramConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;

public class SwitchHandler extends IoHandlerAdapter {

	static private Logger logger = LoggerFactory.getLogger(SwitchHandler.class);

	private DatagramConnector connector;
	private SimpleBufferAllocator allocator;

	public enum State {
		SEEDING,
		CONNECTED,
		OFFLINE
	};
	
	private State state = State.OFFLINE;

	private InetSocketAddress selfAddress = null;

	private Hash selfHash = null;

	private Map<Hash, Line> lines = new MapMaker().makeMap();

	private List<TelexHandler> telexHandlers = Lists.newArrayList();
	
	public SwitchHandler() {
		connector = new NioDatagramConnector();
		connector.setHandler(this);
		allocator = new SimpleBufferAllocator();
		
		addTelexHandler(".see", new SeeHandler());
		addTelexHandler(".tap", new TapHandler());
		addTelexHandler("+end", new EndSignalHandler());
	}
	
	public Line getLine(InetSocketAddress endpoint) {
		return getLine(Hash.of(endpoint));
	}
	
	public Line getLine(Hash endHash) {
		return lines.get(endHash);
	}
	
	public InetSocketAddress getAddress() {
		return selfAddress;
	}

	public Hash getAddressHash() {
		return selfHash;
	}

	public synchronized void addTelexHandler(String key, TelexHandler handler) {
		telexHandlers.add(handler);
	}
	
	public synchronized void removeTelexHandler(TelexHandler handler) {
		telexHandlers.remove(handler);
	}
	
	public void seed(InetSocketAddress bootAddress) {
		send(TelexBuilder
			.to(bootAddress)
			.end(Hash.of(bootAddress).toString()).build());
		state = State.SEEDING;
	}

	public void send(final Map<String, ?> map) {
        ConnectFuture connFuture = connector.connect(parseAddress((String)map.get("_to")));
        connFuture.addListener(new IoFutureListener<ConnectFuture>() {
        	@Override
			public void operationComplete(ConnectFuture future) {
                if (future.isConnected()) {
                    IoSession session = future.getSession();
                    String msg = Json.toJson(map);
                    IoBuffer buffer = allocator.wrap(ByteBuffer.wrap(msg.getBytes()));
                    session.write(buffer);
                }
            }
    	});
	}

	@Override
	public void messageReceived(IoSession session, Object message)
			throws Exception {
		if (message instanceof IoBuffer) {
			IoBuffer buffer = (IoBuffer) message;
			String response = new String(buffer.buf().array());
			logger.debug(formatAddress((InetSocketAddress)session.getRemoteAddress()) + ": " + response);
			Map<String, ?> telex = Json.fromJson(response);
			switch (state) {
			case SEEDING:
				completeBootstrap(session, telex);
				break;
			case CONNECTED:
				processTelex(session, telex);
				break;
			}
		}
	}

	protected void processTelex(IoSession session, Map<String, ?> telex) {
		Line line = getOrCreateLine(parseAddress((String) telex.get("_to")));
		
		Set<String> telexKeys = telex.keySet();
		for (TelexHandler handler : telexHandlers) {
			if (!Sets.intersection(handler.getMatchingKeys(), telexKeys).isEmpty()) {
				handler.telexReceived(this, line, telex);
			}
		}
		
		// TODO: process taps & forward
	}
	
	protected void completeBootstrap(IoSession session, Map<String, ?> telex) {
		state = State.CONNECTED;
		String selfAddrString = (String) telex.get("_to");
		selfAddress = parseAddress(selfAddrString);
		selfHash = Hash.of(selfAddrString);
		logger.debug("SELF[" + selfAddrString + " = " + selfHash + "]");
		
		Line line = getOrCreateLine(selfAddress);
		line.setVisible(true);
//		line.getRules().add(getSwitchRules());
		
		if (selfAddress.equals(session.getRemoteAddress())) {
			logger.info("We're the seed.");
		}
		
		// TODO: start scanning thread
		
		processTelex(session, telex);
	}

	public Line getOrCreateLine(InetSocketAddress endpoint) {
		Hash endHash = Hash.of(endpoint);
		Line line = lines.get(endHash);
		if (line == null) {
			line = new Line(endpoint);
			lines.put(endHash, line);
		}
		return line;
	}
	
	private int time() {
		return (int)(System.currentTimeMillis() / 1000);
	}
	
	/**
	 * Check a line's status.
	 * True if open, false if ringing.
	 */
	public boolean checkLine(Line line, Map<String, ?> telex, int br) {
	    if (line == null) {
	        return false;
	    }
	    
	    Integer _line = (Integer) telex.get("_line");
	    
	    // first, if it's been more than 10 seconds after a line opened, 
	    // be super strict, no more ringing allowed, _line absolutely required
	    if (line.getLineAt() > 0 && time() - line.getLineAt() > 10) {
	        if (line.getLineId() != _line) {
	            return false;
	        }
	    }
	    
	    // second, process incoming _line
	    if (_line != null) {
	        if (line.getRingout() <= 0) {
	            return false;
	        }
	        
	        // must match if exist
	        if (line.getLineId() != 0 && _line != line.getLineId()) {
	            return false;
	        }
	        
	        // must be a product of our sent ring!!
	        if (_line % line.getRingout() != 0) {
	            return false;
	        }
	        
	        // we can set up the line now if needed
	        if (line.getLineAt() == 0) {
	            line.setRingin(_line / line.getRingout()); // will be valid if the % = 0 above
	            line.setLineId(_line);
	            line.setLineAt(time());
	        }
	    }
	    
	    Integer _ring = (Integer) telex.get("_ring");
	    
	    // last, process any incoming _ring's (remember, could be out of order, after a _line)
	    if (_ring != null) {
	    	
	        // already had a ring and this one doesn't match, should be rare
	        if (line.getRingin() >= 0 && _ring != line.getRingin()) {
	            return false;
	        }
	        
	        // make sure within valid range
	        if (_ring <= 0 || _ring > 32768) {
	            return false;
	        }
	        
	        // we can set up the line now if needed
	        if (line.getLineAt() == 0) {
	            line.setRingin(_ring);
	            line.setLineId(line.getRingin() * line.getRingout());
	            line.setLineAt(time());
	        }
	    }
	    
	    Integer _br = (Integer) telex.get("_br");
	    
	    // we're valid at this point, line or otherwise, track bytes
	    logger.debug(
	        "BR " + line.getAddress() + " [" + line.getBr() + " += " 
	        	+ br + "] DIFF " + (line.getBsent() - _br));
	    line.setBr(line.getBr() + br);
	    line.setBrIn(_br);
	    
	    // they can't send us that much more than what we've told them to, bad!
	    if (line.getBr() - line.getBrOut() > 12000) {
	        return false;
	    }
	    
	    // XXX if this is the first seenat,
	    // if we were dialing we might need to re-send our telex as this could be a nat open pingback
	    line.setSeenAt(time());
	    return true;
	}

	/**
	 * Update status of all lines, removing stale ones.
	 * /
	public void scanlines() {
	    var self = this;
	    var now = time();
	    var switches = keys(self.master);
	    var valid = 0;
	    console.log(["SCAN\t" + switches.length].join(""));
	    
	    switches.forEach(function(hash){
	        if (hash == self.selfhash || hash.length < 10) {
	            return; // skip our own endpoint and what is this (continue)
	        }
	        
	        var line = self.master[hash];
	        if (line.end != hash) {
	            return; // empty/dead line (continue)
	        }
	        
	        if ((line.seenat == 0 && now - line.init > 70)
	                || (line.seenat != 0 && now - line.seenat > 70)) {
	            // remove line if they never responded or haven't in a while
	            console.log(["\tPURGE[", hash, " ", line.ipp, "] last seen ", now - line.seenat, "s ago"].join(""));
	            self.master[hash] = {};
	            return;
	        }
	        
	        valid++;
	        
	        if (self.connected) {
	        
	            // +end ourselves to see if they know anyone closer as a ping
	            var telexOut = new Telex(line.ipp);
	            telexOut["+end"] = self.selfhash;
	        
	            // also .see ourselves if we haven't yet, default for now is to participate in the DHT
	            if (!line.visibled++) {
	                telexOut[".see"] = [self.selfipp];
	            }
	            
	            // also .tap our hash for +pop requests for NATs
	            var tapOut = {is: {}};
	            tapOut.is['+end'] = self.selfhash;
	            tapOut.has = ['+pop'];
	            telexOut[".tap"] = [tapOut];
	            self.send(telexOut);
	            
	        }
	    });
	    
	    if (!valid && self.selfipp != self.seedipp) {
	        self.offline();
	        self.startBootstrap();
	    }
	}
	*/
	
	public Collection<Hash> nearTo(final Hash endHash, InetSocketAddress address) {
		Hash addrHash = Hash.of(address);
	    Line addrLine = getLine(addrHash);
	    if (addrLine == null) {
	        return Collections.emptyList(); // should always exist except in startup or offline, etc
	    }
	    
	    // of the existing and visible cached neighbors, sort by distance to this end
	    List<Hash> visibleNeighbors = Lists.newArrayList(
	    	Iterables.filter(addrLine.getNeighbors(),
	    		new Predicate<Hash>() {
		    		@Override
		    		public boolean apply(Hash hash) {
		    			Line line = getLine(hash);
		    			return line != null && line.isVisible();
		    		}
				}));
	    Collections.sort(visibleNeighbors, new Comparator<Hash>(){
	    	@Override
	    	public int compare(Hash o1, Hash o2) {
	    		return endHash.diffBit(o1) - endHash.diffBit(o2);
	    	}
	    });
	    
//	    console.log("near_to: see[]=" + JSON.stringify(see));
//	    console.log("near_to: line=" + JSON.stringify(line));
	    
	    if (visibleNeighbors.isEmpty()) {
	        return Collections.emptyList();
	    }
	    
	    Hash firstSeeHash = visibleNeighbors.get(0);
	    
	    logger.debug(StringUtils.join(
	    		new String[]{
	    			"NEARTO " + endHash,
	    			address.toString(),
	    			endHash.toString(),
	    			Integer.toString(addrLine.getNeighbors().size()),
	    			">",
	    			Integer.toString(visibleNeighbors.size()),
	    			";",
	    			firstSeeHash.toString(),
	    			"=",
	    			Integer.toString(addrLine.getEnd().diffBit(endHash))
	    		}, " "));
	    
	    // it's either us or we're the same distance away so return these results
	    if (firstSeeHash.equals(addrLine.getEnd())
	            || (firstSeeHash.diffBit(endHash) == addrLine.getEnd().diffBit(endHash))) {
	        
	        // this +end == this line then replace the neighbors cache with this result 
	        // and each in the result walk and insert self into their neighbors
	        if (addrLine.getEnd().equals(endHash)) {
	        	logger.debug("NEIGH for " + endHash + " was " 
	        			+ StringUtils.join(addrLine.getNeighbors(), ",") 
	        			+ " " + visibleNeighbors.size());
	        	
	        	addrLine.getNeighbors().clear();
	        	Iterables.addAll(addrLine.getNeighbors(),
	        			Iterables.limit(visibleNeighbors, 5));
	            
	        	logger.debug("NEIGH for " + endHash + " is now " 
	        			+ StringUtils.join(addrLine.getNeighbors(), ",") 
	        			+ " " + visibleNeighbors.size());
	        	
	        	for (Hash neighborHash : addrLine.getNeighbors()) {
	        		Line neighborLine = getLine(neighborHash);
	        		if (neighborLine == null) {
	        			continue;
	        		}
	        		
	        		neighborLine.getNeighbors().add(endHash);
                    logger.debug("SEED " + address + " into " + neighborLine.getAddress());
	        	}
	        	
	        }
	        
	        logger.debug("SEE distance=" + endHash.diffBit(firstSeeHash) 
	        		+ " count=" + visibleNeighbors.size());
	        return visibleNeighbors;
	    }

	    // whomever is closer, if any, tail recurse endseeswitch them
	    return nearTo(endHash, getLine(firstSeeHash).getAddress());
	}
	
}