package com.team1160.assistant.commands.vision;

import com.team1160.assistant.commands.CommandBase;

public class visionDoNothing extends CommandBase{
    
    
    public visionDoNothing(){
        requires(vision);
    }
    
    protected void initialize() {
    }

    protected void execute() {
    }

    protected boolean isFinished() {
        return false;
    }

    protected void end() {
    }

    protected void interrupted() {
    }

}
