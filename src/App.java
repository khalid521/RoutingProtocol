
import java.net.DatagramPacket;
        import java.net.DatagramSocket;
        import java.net.InetAddress;
        import java.io.*;
        import java.util.*;

public class App extends Thread
{
    static String configurationFile = ""; // The name of the config file for this node
    static float neighbourList[] = new float[100]; // a list that will store all the neighbours of this node
    static int actualNeighbourCount = 0; // actual number of neighbours in the list
    static int neighbourCount = 0; // the number of neighbours that is found in the config file
    static int nodeCount = 0; // the number of nodes this node know about
    static int serverPort; // port that this node is listening at
    static char myNodeName; // this node's name
    static int cyclesUnchanged = 0; // number of cycles where distanceVector was unchanged
    static float[][] distanceVector = new float[100][6]; // the distance vector
    static boolean resultPrinted = false; // results printed or not
    static float[] deadNodeList = new float[100]; // a list that will store all the dead nodes
    static int deadNodeCount = 0;  // Number of dead nodes
    static int deadNeighbourCount = 0; // Number of dead neighbours (dead nodes that are neighbours as well)
    static float deadNodeExist = -1;
    static boolean poisonedReverseMode = false; // Poisoned reverse wanted or not
    static float[][] poisonedReverseCosts = new float[100][2]; // costs before and after poisoned reverse
    static boolean linkCostUpdated = false; // link costs updated because of poisoned reverse or not yet
    static int neighbourInformedCount = 0; // number of nodes informed about the poisoned reverse
    static int usePoisonedReverseCosts = -10; // use the poisoned reverse costs or the regular costs
    static int infinity = 10000; // a large value to represent infinity

    // This function starts a new thread that will be responsible for receiving the UDP messages.
    // This function makes a call to the function run().
    public App()
    {
        start();
    }

    // This function keeps receiving the UDP messages all the time and updating the Distance Vector table accordingly
    public void run()
    {
        float deadNode = -1;
        int buffer_size = 1024;
        byte buffer[] = new byte[buffer_size];
        DatagramPacket p;
        String ReceivedMessage;
        try
        {
            // open a new socket for listening
            DatagramSocket udpSocket = new DatagramSocket(serverPort);

            while (true)
            {
                p = new DatagramPacket(buffer, buffer.length);
                // Receive a message when it arrives
                udpSocket.receive(p);
                // save the message data in a string
                ReceivedMessage = new String(p.getData(), 0, p.getLength());
                // call this function to update the DV based on the message
                DistanceVectorUpdate(ReceivedMessage);

                // This will happen only after the initial results are printed.
                if (resultPrinted)
                {
                    // Check if there are failed nodes. deadNode will store the array index of the dead node
                    deadNode = FindFailedNodes();
                    // -1 means no dead nodes. if != -1 means there is a dead node
                    if (deadNode != -1)
                    {
                        // find the node name of the dead node
                        float deadNodeName = distanceVector[(int)deadNode][0];
                        // then add it to the dead node list
                        if (AddDeadNode(deadNodeName)) // if the node added successfully
                            deadNodeExist = deadNodeName;
                        // Read file again to reinitialize the costs and start calculating them again without considering the dead nodes
                        distanceVector = ReadConfigurationFile(configurationFile, deadNodeList, deadNeighbourCount);
                        deadNodeName = -1;
                    }
                }
            }
        }
        catch (java.io.IOException e)
        {
        }
    }

    public static void main(String args[]) throws Exception
    {
        if (args.length >= 3 && args.length <= 4)
        {
            // create a new udp socket
            DatagramSocket udpSocket = new DatagramSocket(serverPort);
            myNodeName = args[0].charAt(0);
            serverPort = Integer.parseInt(args[1]);
            configurationFile = args[2];
            if (args.length == 4)
            {
                if (args[3].equals("-p"))
                    poisonedReverseMode = true;
                else{
                    System.out.println("Incorrect usage of the command.");
                    return;
                }
            }
            // Read config file and create the DV table
            distanceVector = ReadConfigurationFile(configurationFile, deadNodeList, deadNeighbourCount);

            // Send DV to neighbours
            SendCostsToNeighbours(udpSocket, deadNodeExist);
            // Call this function to start a new thread for receiving messages.
            new App();

            while (true)
            {
                // wait 5 seconds (5000 milli seconds) before sending the DV again
                Thread.sleep(5000);
                // resend the DV table
                SendCostsToNeighbours(udpSocket, deadNodeExist);
                // If you receive (nodecount * 3) messages with no changes, this means that the DV is ready to be printed
                if (cyclesUnchanged > (nodeCount * 3) && !resultPrinted)
                {
                    // print results
                    PrintResults();
                    resultPrinted = true;
                }

                // if we are in PR mode and we printed the results, we take the PR costs from the file and start the process of creating the DV again
                if (resultPrinted && poisonedReverseMode && !linkCostUpdated)
                {
                    // send costs again
                    SendCostsToNeighbours(udpSocket, deadNodeExist);
                    linkCostUpdated = true;
                    // update costs using the second cost feild from the config file
                    UpdateLinkCost();

                    cyclesUnchanged = 0;
                    resultPrinted = false;
                    Thread.sleep(10000);
                }
            }
        }
        else
            System.out.println("Incorrect usage of the command.");

    }

    // This function reads the config file and store its value in the DV table
    static float[][] ReadConfigurationFile(String fn, float[] deadNode, int DNC)
    {
        try
        {
            float[][] tempDistVector = new float[100][6];
			/*
			 * tempDistVector[i][0] = node name
			 * tempDistVector[i][1] = cost
			 * tempDistVector[i][2] = port
			 * tempDistVector[i][3] = via
			 * tempDistVector[i][4] = 1 if neighbour, 0 otherwise.
			 * tempDistVector[i][5] = number of messages received after printing the results
			 */

            BufferedReader in = new BufferedReader(new FileReader(fn));
            // read first line
            String strLine = in.readLine();
            char NodeName;
            // first line has the number of neighbours
            neighbourCount = Integer.parseInt(strLine);
            // initially, each node in the network only knows its neighbours
            nodeCount = neighbourCount;
            for (int i = 0; i < neighbourCount; i++)
            {
                // read a line representing a node
                strLine = in.readLine();
                StringTokenizer fields = new StringTokenizer(strLine);
                // first token is the node name
                NodeName = fields.nextToken().charAt(0);
                // check that this node is not dead
                if (!nodeIsDead((float)NodeName))
                {
                    tempDistVector[i][0] = (float)NodeName;
                    tempDistVector[i][1] = Float.valueOf(fields.nextToken()).floatValue();
                    if (poisonedReverseMode)
                    {
                        poisonedReverseCosts[i][0] = tempDistVector[i][0];
                        poisonedReverseCosts[i][1] = Float.valueOf(fields.nextToken()).floatValue();
                    }
                    if (DNC == usePoisonedReverseCosts)
                    {
                        tempDistVector[i][1] = poisonedReverseCosts[i][1];
                    }
                    tempDistVector[i][2] = Float.valueOf(fields.nextToken()).floatValue();
                    tempDistVector[i][3] = (float)NodeName;
                    tempDistVector[i][4] = 1;
                    tempDistVector[i][5] = 0;
                    // add this node to neighbours list
                    AddNeighbour((float)NodeName);
                }
                else
                {
                    // if one of the nodes is dead, then we decrement the neighbourCount
                    neighbourCount--;
                    i--;
                }

            }
            nodeCount = neighbourCount;
            cyclesUnchanged = 0;
            resultPrinted = false;
            in.close();
            return tempDistVector;
        }
        catch (IOException e)
        {
            return null;
        }

    }

    // This function sends the DV to all neighbours
    static void SendCostsToNeighbours(DatagramSocket udpSocket, float deadNode) throws Exception
    {
        int buffer_size = 1024;
        byte buffer[] = new byte[buffer_size];
        String Message = "";

        // Prepare the message
        // Format:
        // MyName	numberOfNodesIamSending	MyPort	deasNodeExistsOrNot	<nodeName	costFromMetoNode	NodePort	IReachItVia>
        // feilds between these brackets <> are repeated for each node in my DV table
        Message += myNodeName + "\t" + nodeCount + "\t" + serverPort + "\t" + deadNode + "\t";
        for (int i = 0; i < nodeCount; i++)
        {
            Message += (char)distanceVector[i][0] + "\t" + distanceVector[i][1] + "\t" + distanceVector[i][2] + "\t" + (char)distanceVector[i][3] + "\t";
        }
        // Write message in a byte array
        for (int k = 0; k < Message.length(); k++)
        {
            buffer[k] = (byte)Message.charAt(k);
        }
        // for all nodes I know
        for (int j = 0; j < nodeCount; j++)
        {
            // if this node is a neighbour and is not dead
            if (nodeIsInNeighbours(distanceVector[j][0]) && !nodeIsDead(distanceVector[j][0]))
            {
                // if we are in PR mode we have to update the message as follows
                if (poisonedReverseMode)
                {
                    Message = "";
                    if (linkCostUpdated && neighbourInformedCount<neighbourCount)
                    {
                        Message += myNodeName + "\t" + nodeCount + "\t" + serverPort + "\t" + usePoisonedReverseCosts + "\t";
                        Message += preparePoisonedReverseOutput(distanceVector[j][0]);
                        neighbourInformedCount++;
                    }
                    else
                    {
                        Message += myNodeName + "\t" + nodeCount + "\t" + serverPort + "\t" + deadNode + "\t";
                        Message += preparePoisonedReverseOutput(distanceVector[j][0]);
                    }
                    for (int k = 0; k < Message.length(); k++)
                    {
                        buffer[k] = (byte)Message.charAt(k);
                    }
                }

                // send message to this node
                udpSocket.send(new DatagramPacket(buffer, Message.length(), InetAddress.getLocalHost(), (int)distanceVector[j][2]));
            }
        }
        deadNodeExist = -1;
    }

    // This function reads the message received from neighbours and updates the DV accordingly
    static void DistanceVectorUpdate(String messageReceived)
    {
        StringTokenizer fields = new StringTokenizer(messageReceived);
        char senderName = fields.nextToken().charAt(0);
        int numberOfSentNodes = Integer.parseInt(fields.nextToken()); //numberOfSentNodes=Sender's Nodes Count
        int senderPort = Integer.parseInt(fields.nextToken());
        float isDead = Float.parseFloat(fields.nextToken());
        char node, via;
        float cost, port;
        // after printing the results, we count the messages received from all nodes to know if there is a dead node
        // a dead node is a node that is sending messages less than other nodes by 3 or more
        if (resultPrinted)
        {
            // update the number of received messages from this node
            nodeIsAlive(senderName);
        }

        // if the message is telling me that this is the PR cost
        if (isDead == usePoisonedReverseCosts)
        {
            // Read the config file again and this time use the PR costs
            distanceVector = ReadConfigurationFile(configurationFile, deadNodeList, usePoisonedReverseCosts);
        }
        // This means that there is a dead node
        else if (isDead != -1 && isDead != usePoisonedReverseCosts)
        {
            // Add the dead node to the list
            if (AddDeadNode(isDead))
            {
                deadNodeExist = isDead;
            }
            // then read the config considering that there is a dead node now
            distanceVector = ReadConfigurationFile(configurationFile, deadNodeList, deadNeighbourCount);
        }
        for (int i = 0; i < numberOfSentNodes; i++)
        {
            node = fields.nextToken().charAt(0);
            cost = Float.parseFloat(fields.nextToken());
            port = Float.parseFloat(fields.nextToken());
            via = fields.nextToken().charAt(0);
            if (!nodeIsDead((float)node))
            {
                // Change the DV using these values
                DistanceVectorChange(node, cost, port, via, senderName, senderPort);
            }
        }
        return;

    }

    // This function receives a node and its cost from a sender.
    // Then it updates this node's cost in the DV accordingly
    static void DistanceVectorChange(char Node,float Cost,float Port, char Via, char SN, int SP)
    {
        float newCost = 0;
        float oldCost = 0;
        if (Node != myNodeName)
        {
            // If I don't know this node, I add it to the DV
            if (!nodeIsInDistanceVector(Node))
            {
                distanceVector[nodeCount][0] = (float)Node;
                distanceVector[nodeCount][1] = Cost + GetCost(SN);
                distanceVector[nodeCount][2] = Port;
                distanceVector[nodeCount][3] = (float)SN;
                distanceVector[nodeCount][4] = 0;
                distanceVector[nodeCount][5] = 0;
                nodeCount++;
                cyclesUnchanged = 0;
                resultPrinted = false;
            }
            // else, I check if I can reach it with less cost via the sender (SN), I update the DV.
            else
            {
                oldCost = GetCost(Node);
                newCost = GetCost(SN) + Cost;
                if (newCost < oldCost)
                {
                    for (int j = 0; j < nodeCount; j++)
                    {
                        if (distanceVector[j][0] == SN)
                        {
                            // If I reach the sender through one of my neighbours
                            if (nodeIsInNeighbours(distanceVector[j][3]))
                                // I update the cost and update the "via" feild to be this neighbour
                                UpdateNodeCost(Node, (char)distanceVector[j][3], newCost, 0);
                            else
                                // else, I use the sender name as the "via"
                                UpdateNodeCost(Node, SN, newCost, 0);
                        }
                    }
                    cyclesUnchanged = 0;
                    resultPrinted = false;
                }
                else
                {
                    // if this message doesn't change a thing, I increment the cyclesUnchaanged.
                    cyclesUnchanged++;
                }
            }
        }
        else // If node is myName
        {
            // If the sender is not in DV and it reaches me directly
            // This means it is a neighbour that I don't know of yet
            // So I add it to the DV
            if (!nodeIsInDistanceVector(SN) && Via == myNodeName)
            {
                if (poisonedReverseMode)
                {
                    poisonedReverseCosts[nodeCount][1] = Cost;
                    distanceVector[nodeCount][1] = Cost;
                }
                else
                    distanceVector[nodeCount][1] = Cost;

                distanceVector[nodeCount][0] = (float)SN;
                distanceVector[nodeCount][2] = SP;
                distanceVector[nodeCount][3] = (float)SN;
                distanceVector[nodeCount][4] = 1;
                distanceVector[nodeCount][5] = 0;
                nodeCount++;
                cyclesUnchanged = 0;
                resultPrinted = false;
                AddNeighbour((float)SN);
            }
            // If the node is in DV and reaches me directly
            else if (nodeIsInDistanceVector(SN) && Via == myNodeName)
            {
                if (!poisonedReverseMode)
                {
                    for (int x = 0; x < nodeCount; x++)
                    {
                        if ((char)distanceVector[x][0] == SN)
                            distanceVector[x][4] = 1;
                    }
                    // try adding it to the neighbour list
                    AddNeighbour((float)SN);
                    oldCost = GetCost(SN);
                    newCost = Cost;
                    // if it reaches me with lower cost then I should update its record in the DV
                    if (newCost < oldCost)
                    {
                        for (int h = 0; h < nodeCount; h++)
                        {
                            if (distanceVector[h][0] == SN)
                            {
                                if (nodeIsInNeighbours(distanceVector[h][3]))
                                    UpdateNodeCost(SN, (char)distanceVector[h][3], newCost, 0);
                                else
                                    UpdateNodeCost(SN, SN, newCost, 0);
                            }
                        }
                        cyclesUnchanged = 0;
                        resultPrinted = false;
                    }
                    else
                    {
                        cyclesUnchanged++;
                    }
                }
                else // if PR mode
                {
                    if (!nodeHasPoisonedReverse(SN, Cost)) // if this node PR cost is the same as its original cost
                    {
                        for (int x = 0; x < nodeCount; x++)
                        {
                            if ((char)distanceVector[x][0] == SN)
                                distanceVector[x][4] = 1;
                        }
                        // try adding it to the neighbour list
                        AddNeighbour((float)SN);
                        oldCost = GetCost(SN);
                        newCost = Cost;
                        // if cost is less update DV
                        if (newCost < oldCost)
                        {
                            UpdateNodeCost(SN, SN, newCost, 0);
                            cyclesUnchanged = 0;
                            resultPrinted = false;
                        }
                        else
                        {
                            cyclesUnchanged++;
                        }
                    }
                }
            }
            // If node not in DV and doesn't reach me directly
            else if (!nodeIsInDistanceVector(SN) && Via != myNodeName)
            {
                // If the node it is reaching me via is a neighbour
                // Then I can add this sender node to my DV and to my neighbours
                if (nodeIsInNeighbours(Via))
                {
                    if (poisonedReverseMode)
                    {
                        poisonedReverseCosts[nodeCount][1] = Cost;
                        distanceVector[nodeCount][1] = Cost;
                    }
                    else
                        distanceVector[nodeCount][1] = Cost;

                    distanceVector[nodeCount][0] = (float)SN;
                    distanceVector[nodeCount][2] = SP;
                    distanceVector[nodeCount][3] = (float)Via;
                    distanceVector[nodeCount][4] = 1;
                    distanceVector[nodeCount][5] = 0;
                    nodeCount++;
                    cyclesUnchanged = 0;
                    resultPrinted = false;
                    AddNeighbour((float)SN);
                }
                else
                    // else, I ignore it
                    cyclesUnchanged++;
            }
            // If node is in DV and reaches me indirectly
            else if (nodeIsInDistanceVector(SN) && Via != myNodeName)
            {
                // If the node it is reaching me via is a neighbour
                // Then I can update this sender's DV record.
                if (nodeIsInNeighbours(Via))
                {
                    if (!poisonedReverseMode)
                    {
                        for (int x = 0; x < nodeCount; x++)
                        {
                            if ((char)distanceVector[x][0] == SN)
                                distanceVector[x][4] = 1;
                        }
                        AddNeighbour((float)SN);
                        oldCost = GetCost(SN);
                        newCost = Cost;
                        if (newCost < oldCost && newCost >= GetCost(Via))
                        {
                            UpdateNodeCost(SN, Via, newCost, 0);
                            cyclesUnchanged = 0;
                            resultPrinted = false;
                        }
                        else
                        {
                            cyclesUnchanged++;
                        }
                    }
                    else
                    {
                        for (int x = 0; x < nodeCount; x++)
                        {
                            if ((char)distanceVector[x][0] == SN)
                                distanceVector[x][4] = 1;
                        }
                        AddNeighbour((float)SN);
                        oldCost = GetCost(SN);
                        newCost = Cost;
                        if (newCost < oldCost && newCost >= GetCost(Via))
                        {
                            UpdateNodeCost(SN, Via, newCost, 0);
                            cyclesUnchanged = 0;
                            resultPrinted = false;
                        }
                        else
                        {
                            cyclesUnchanged++;
                        }
                    }
                }
                else
                    // else, I ignore it
                    cyclesUnchanged++;
            }
        }
        return;
    }

    // Returns true if a node is my DV, false otherwise.
    static boolean nodeIsInDistanceVector(char Node)
    {
        for (int i = 0; i < nodeCount; i++)
        {
            if ((char)distanceVector[i][0] == Node)
                return true;
        }
        return false;
    }

    // Return the cost of a node from my DV
    static float GetCost(char Node)
    {
        for (int i = 0; i < nodeCount; i++)
        {
            if ((char)distanceVector[i][0] == Node)
                return distanceVector[i][1];
        }
        return infinity;
    }

    // Updates the node's cost and values
    static void UpdateNodeCost(char Node, char SN, float newCost, float IsAlive)
    {
        for (int i = 0; i < nodeCount; i++)
        {
            if ((char)distanceVector[i][0] == Node)
            {
                if (IsAlive == 0)
                {
                    distanceVector[i][1] = newCost;
                    distanceVector[i][3] = (float)SN;
                }
                else
                    distanceVector[i][5] += IsAlive;
            }
        }
        return;
    }

    // This function checks if there is a dead node it returns its order in the distanceVectore table
    // Otherwise, it returns -1
    static float FindFailedNodes()
    {
        int Dif = 0;
        for (int i = 0; i < nodeCount; i++)
        {
            if (nodeIsInNeighbours(distanceVector[i][0]))
            {
                Dif = 0;
                for (int k = 0; k < nodeCount; k++)
                {
                    // if this node sent less messages (less by 3) than other nodes and this node is a neighbour
                    if ((distanceVector[i][5] + 2) < distanceVector[k][5] && distanceVector[k][4]==1)
                    {
                        Dif++;
                    }
                }
            }
            // if dif>1 means this node sent less messages than at least two other node.
            // Which is an indication of a dead node
            if (Dif > 1)
            {
                return i;
            }
        }
        return -1;
    }

    // Add node to dead node list
    static boolean AddDeadNode(float deadNode)
    {
        for (int i = 0; i < nodeCount; i++)
        {
            if (distanceVector[i][0] == deadNode)
            {
                for (int k = 0; k < deadNodeCount; k++)
                {
                    if (deadNodeList[k] == deadNode)
                        return false;
                }
                deadNodeList[deadNodeCount] = deadNode;
                deadNodeCount++;
                if (distanceVector[i][4] == 1)
                    deadNeighbourCount++;
                return true;
            }
        }
        return false;
    }

    // Add neighbour to neighbour's list
    static boolean AddNeighbour(float N)
    {
        for (int k = 0; k < actualNeighbourCount; k++)
        {
            if (neighbourList[k] == N)
                return false;
        }
        neighbourList[actualNeighbourCount] = N;
        actualNeighbourCount++;
        return true;
    }

    // read config file again using PR costs this time
    static void UpdateLinkCost()
    {
        distanceVector = ReadConfigurationFile(configurationFile, deadNodeList, usePoisonedReverseCosts);
    }

    // return true if node is in deadNodeList, false otherwise.
    static boolean nodeIsDead(float N)
    {
        for (int i = 0; i < deadNodeCount; i++)
        {
            if (deadNodeList[i] == N)
                return true;
        }
        return false;
    }

    // Updates the number of messages received from this node after printing the results
    // this number is used to indicate whether a node is alive or dead.
    static void nodeIsAlive(char N)
    {
        for (int i = 0; i < nodeCount; i++)
        {
            if ((char)distanceVector[i][0] == N)
            {
                distanceVector[i][5]++;
            }
        }
        return;
    }

    // returns true if node is in neighbourList, false otherwise.
    static boolean nodeIsInNeighbours(float N)
    {
        for (int i = 0; i < actualNeighbourCount; i++)
        {
            if (neighbourList[i] == N)
                return true;
        }
        return false;
    }

    // return true if this node PR cost is different from its original cost
    static boolean nodeHasPoisonedReverse(float N, float C)
    {
        for (int i = 0; i < neighbourCount; i++)
        {
            if (poisonedReverseCosts[i][0] == N)
            {
                if (poisonedReverseCosts[i][1] == C)
                    return false;
                else
                    return true;
            }
        }
        return false;
    }

    // Prints the results
    static void PrintResults()
    {
        for (int i = 0; i < nodeCount; i++)
        {
            System.out.println("Shortest path to node \"" + (char)distanceVector[i][0]
                    + "\": The next hop is \"" + (char)distanceVector[i][3]
                    + "\" and the cost is " + distanceVector[i][1]);
        }
        System.out.println("\n\n");
    }

    // prepares the message that will be sent in PR mode.
    static String preparePoisonedReverseOutput(float N)
    {
        String M = "";
        for (int i = 0; i < nodeCount; i++)
        {
            if (N == distanceVector[i][3] && distanceVector[i][0]!=N)
            {
                M += (char)distanceVector[i][0] + "\t" + "999.0" + "\t" + distanceVector[i][2] + "\t" + (char)distanceVector[i][3] + "\t";
            }
            else
                M += (char)distanceVector[i][0] + "\t" + distanceVector[i][1] + "\t" + distanceVector[i][2] + "\t" + (char)distanceVector[i][3] + "\t";
        }
        return M;
    }


}
