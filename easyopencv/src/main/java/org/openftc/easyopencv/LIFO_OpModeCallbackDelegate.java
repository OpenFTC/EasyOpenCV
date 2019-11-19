package org.openftc.easyopencv;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;

import org.firstinspires.ftc.robotcore.internal.opmode.OpModeManagerImpl;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

import java.util.Stack;

public class LIFO_OpModeCallbackDelegate implements OpModeManagerNotifier.Notifications
{
    private static LIFO_OpModeCallbackDelegate theInstance;

    public static LIFO_OpModeCallbackDelegate getInstance()
    {
        if(theInstance == null)
        {
            theInstance = new LIFO_OpModeCallbackDelegate();

            OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).registerListener(theInstance);
        }

        return theInstance;
    }

    private Stack<OnOpModeStoppedListener> stack = new Stack<>();

    public synchronized void add(OnOpModeStoppedListener item)
    {
        stack.push(item);
    }

    public interface OnOpModeStoppedListener
    {
        void onOpModePostStop(OpMode opMode);
    }

    @Override
    public synchronized void onOpModePostStop(OpMode opMode)
    {
        while (!stack.isEmpty())
        {
            stack.pop().onOpModePostStop(opMode);
        }

        theInstance = null;
        OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getActivity()).unregisterListener(theInstance);
    }

    //-----------------------------------------------------------
    // Not used
    //-----------------------------------------------------------

    @Override
    public synchronized void onOpModePreInit(OpMode opMode)
    {

    }

    @Override
    public synchronized void onOpModePreStart(OpMode opMode)
    {

    }
}
